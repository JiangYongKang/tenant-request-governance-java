# 多租户请求治理服务（tenant-request-governance-java）

基于 Spring Boot 3 / Java 21 的多租户请求治理能力演示。核心治理状态（配额、幂等、审计）
使用**本地内存**承载，并提供**本地快照**把治理状态导出为带版本与校验和的文件、在服务重启
或运行期整体接回。并发下提供一致且可解释的结论：

- 按 **租户 + 操作** 维度的固定窗口配额限流，并发计数准确；
- 写操作 **幂等提交**，重复提交返回首次结果，业务副作用只发生一次；
- **租户 + 角色** 访问控制，越权 / 跨租户被拒绝且原因可区分；
- 每个被接受或被拒绝的关键操作都留下**单调递增、只追加**的审计记录；
- **治理状态快照**：一致导出、原子恢复、版本/校验和保护，坏快照明确拒绝且不污染在线状态。

---

## 1. 快速开始

```bash
# 构建 + 全部单测（26 个用例）
mvn test

# 启动服务
mvn spring-boot:run
```

启动时自动注册两个演示租户并下发窗口配额（窗口长度 60s，见 `DataInitializer`）：

| 租户 | 角色 | CREATE_ORDER 窗口配额 |
|------|------|----------------------|
| tenant-a | READER / WRITER / ADMIN | 3 |
| tenant-b | READER / WRITER / ADMIN | 5 |

请求头约定：

| Header | 含义 |
|--------|------|
| `X-Tenant-Id` | 调用方租户 |
| `X-Role` | `READER` / `WRITER` / `ADMIN` |
| `X-Request-Id` | 请求标识（可选，缺省自动生成 `req-uuid`） |

---

## 2. 限流（配额）语义

- **维度**：`(tenantId, action)`，每个维度拥有**独立的固定窗口对象与锁**。
  因此单个租户的突发流量只会竞争它自己的锁、消耗它自己的计数，
  **不影响其它租户的可用性**（见 `QuotaBoundaryTest#burstInOneTenantDoesNotAffectOtherTenant`）。
- **固定窗口**：窗口内 `incrementAndGet` 后计数 `> limit` 即拒绝，并立即 `decrementAndGet`
  回退，保证被拒绝请求不占用计数、窗口计数严格准确
  （见 `QuotaBoundaryTest#concurrentRequestsCountAccurately`：100 并发 / 配额 20，恰好放行 20）。
- **边界**：第 `limit` 个请求放行，第 `limit+1` 个返回 `429 QUOTA_EXCEEDED`，
  响应体携带 `used/limit`，判定可解释。
- **窗口滚动**：窗口过期后计数归零重新计数。
- **运行期热更新**：`POST /api/admin/quota` 只修改上限，**不清空、不重置当前窗口与已有计数**；
  立即生效，且不与其它租户/操作串用（见 `quotaHotUpdateTakesEffectImmediatelyAndPreservesCount`）。
- **配额与幂等的关系**：命中已生效结果的**重放先于配额判定直接返回**，不占配额；
  只有真正“首次执行业务”的请求才消耗配额，并发合流来的重放与内容冲突会**回退预占**，
  不会因重复提交耗尽窗口。

### 拒绝响应示例（429）

```json
{
  "error": "GOVERNANCE_REJECTED",
  "reason": "QUOTA_EXCEEDED",
  "message": "quota exhausted for tenant tenant-a (used 3/3 in window)",
  "auditSeq": 7,
  "requestId": "req-xxxx"
}
```

---

## 3. 幂等语义

- **键维度**：`tenantId + idempotencyKey`。幂等键只在同一租户内去重，不同租户互不影响。
- **首次提交**：执行业务，产生一次真实副作用（`OrderService` 内部计数 +1），返回结果并缓存。
- **重复提交（相同内容）**：返回**首次结果**，`replayed=true`，业务副作用**不再次发生**。
- **同键不同内容**：对请求体做 SHA-256 摘要比对，不一致立即返回
  `409 IDEMPOTENCY_CONFLICT`，明确拒绝（区别于正常重放）。
- **并发重复提交**：使用 `ConcurrentHashMap.compute` 原子地“判占位 + 放占位”，
  后到线程通过 `CompletableFuture` **合流等待**首个请求，因此任意并发度下副作用恰好一次
  （见 `IdempotencyConcurrencyTest#concurrentDuplicatesProduceSingleSideEffect`：
  32 并发、同一幂等键，唯一订单数 = 1、副作用次数 = 1、其余全部 REPLAY）。
- 首次执行业务失败时移除占位，允许后续用相同键重试。
- **重放先于限流**：治理链路先做幂等只读判定，命中已生效结果的重放（含快照恢复后的重放）
  直接返回首次结果、**不进入配额判定**，因此窗口打满后重放仍成功，也不会因重放耗尽窗口；
  只有真正“首次执行”的请求才占用配额（并发合流来的重放会在事后回退预占）。

---

## 4. 租户隔离与访问规则

判定顺序（先于配额与业务，被拒不产生副作用）：

1. 调用方租户未注册 → `403 UNKNOWN_TENANT`
2. 角色不属于该租户授权集合 / 未知角色 → `403 FORBIDDEN_ROLE`
3. 访问**其它租户**资源且角色非 `ADMIN` → `403 CROSS_TENANT_ACCESS`
4. `READER` 执行写操作 → `403 FORBIDDEN_ROLE`

角色权限：

| 角色 | 本租户读 | 本租户写 | 跨租户访问 |
|------|----------|----------|------------|
| READER | 是 | 否 | 否 |
| WRITER | 是 | 是 | 否 |
| ADMIN | 是 | 是 | 是 |

资源归属租户由请求体 `tenantId` 指定（缺省为调用方租户）。三类拒绝原因彼此可区分
（见 `AccessControlTest#rejectReasonsAreDistinguishable`）。

---

## 5. 审计

- `AuditService` 使用 `AtomicLong` 生成序号，记录写入 `synchronizedList`，
  序号从 1 开始**连续单调递增、只追加**，不会被后续操作覆盖或错序。
- 每个关键操作都留痕，决策类型：`ACCEPT`（首次接受）、`REPLAY`（幂等重放）、
  `REJECT`（访问控制 / 限流 / 冲突拒绝），并记录判定依据 `basis`。
- 查询：`GET /api/admin/audit`（按序号顺序返回）。
- 并发下连续性见 `AuditOrderingTest`：40 线程混合接受/拒绝后，序号严格 1..N 无缺口。

---

## 6. 治理状态快照（导出 / 恢复）

为解决“配额计数、幂等结果、审计流水只活在内存里，重启或换环境就得从头再来”的问题，
服务支持把当前治理状态**整体导出为一份本地快照文件**，并在服务重启或运行期**整体接回**。

### 6.1 快照包含哪些状态

| 部分 | 内容 | 接回后的效果 |
|------|------|--------------|
| 配额窗口 `quotas` | 每个 `(租户, 操作)` 的上限 `limit`、窗口起点 `startMillis`、窗口内已用 `count` | 未过期窗口的已用计数继续算进限流；恢复时**已过期**的窗口把计数折叠为 0（上限保留） |
| 幂等结果 `idempotentResults` | 已**生效**提交的 `租户 + 幂等键 + 请求摘要 SHA-256 + 首次业务结果` | 同租户同键再提交返回**第一次的结果**，业务副作用**不再发生一次**；同键不同内容仍判冲突；处理中/失败的占位不导出 |
| 审计流水 `audit` | 当前序号 `lastSeq` 与按序号排列的全部条目 | 新记录严格从 `lastSeq+1` 继续编号，不重复、不回退、不错序 |
| 租户注册 `tenants` | 租户及其授权角色集合 | 访问控制随快照一起接回，自包含可换环境 |

此外还记录 `formatVersion`（快照格式版本）、`takenAtMillis`（导出时刻）、
`windowMillis`（固定窗口长度）。`windowMillis` 必须与运行实例一致，否则视为语义不兼容而拒绝。

### 6.2 文件格式与版本兼容

快照是一个人类可读的三段式文本文件（JSON 状态本体 + 头部保护）：

```text
GOVERNANCE-SNAPSHOT-V1
sha256:<JSON 本体的 SHA-256 十六进制摘要>
{
  "formatVersion" : 1,
  "windowMillis" : 60000,
  "takenAtMillis" : 1728000000000,
  "quotas" : [ ... ],
  "idempotentResults" : [ ... ],
  "audit" : { "lastSeq" : 3, "entries" : [ ... ] },
  "tenants" : [ ... ]
}
```

- **版本范围**：当前为 **v1**，本构建支持恢复的版本区间为 `1..1`（`MIN_SUPPORTED_VERSION..FORMAT_VERSION`）。
  头部魔数版本与 JSON 内 `formatVersion` 任一不被支持都会被拒绝。
- **损坏保护**：恢复时先校验魔数，再用 `MessageDigest.isEqual` 对 JSON 本体做
  **SHA-256 恒定时间比对**。版本不符、JSON 损坏、校验和不符（含写一半被截断）、
  `windowMillis` 不一致、空字段、审计断号/`lastSeq` 不一致等情况都会抛
  `SnapshotException`（HTTP `400 SNAPSHOT_REJECTED / INVALID_SNAPSHOT`）。
- **失败不污染**：所有解析与校验都在触碰在线状态**之前**完成；只有全部通过后才进入写锁做
  原子切换。因此坏快照被拒绝后，服务仍按原状态正常限流、幂等、审计。
- **写盘原子性**：导出先写同目录临时文件（`.xxx.tmp-uuid`），fsync 后再
  `ATOMIC_MOVE`（文件系统不支持时退化为普通替换）发布；写到一半失败只留下/清理临时文件，
  **不会破坏已有的好快照**。

### 6.3 一致性与并发语义

- **在线请求持读锁，快照状态收集/恢复持写锁**（`GovernanceGate`，公平读写锁）。
- 导出时的状态收集在写锁内一次性完成，得到的是**某一时刻的全局一致切片**，
  不会出现一半旧值一半新值拼出来的结果；磁盘 IO 在锁外进行，正常写入不会被长时间卡住。
- 恢复先在锁外完成读盘与全部校验，再进入写锁等待在途请求结束，随后四个组件
  **依次原子换表**。对外要么整体恢复成功、要么完全看不到恢复，不存在“带着半套状态”对外服务。
- 接回后的在线判定仍是哈希表 O(1) 路径，历史数据量不影响响应延迟
  （见 `largeRestoreKeepsOnlineDecisionLatencyLow`，1.2 万历史记录下测在线判定 p95）。

### 6.4 怎么用

运行期 HTTP 接口（也可直接调用 `SnapshotManager`）：

```bash
# 导出到指定路径（不传 path 时用默认文件 governance-snapshot.snap）
curl -s -X POST 'http://localhost:8080/api/admin/snapshots/export?path=/tmp/gov.snap'
# {"operation":"EXPORT","formatVersion":1,"takenAtMillis":...,"path":"/tmp/gov.snap",
#  "quotaWindows":2,"idempotentResults":3,"auditEntries":3,"tenants":2}

# 运行期恢复（整体生效）
curl -s -X POST 'http://localhost:8080/api/admin/snapshots/restore?path=/tmp/gov.snap'
```

重启时自动接回（可选，默认关闭），在 `application.properties` 指定快照路径即可，
启动恢复早于演示数据初始化，演示数据仅“补缺”、不覆盖快照接回的租户与配额：

```properties
governance.snapshot.restore-on-start=/tmp/gov.snap
```

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--governance.snapshot.restore-on-start=/tmp/gov.snap
```

### 6.5 失败时怎么处理

| 现象 | 行为 | 处理建议 |
|------|------|----------|
| 版本不支持（头部或 payload） | 拒绝恢复，状态不变 | 用同版本服务导出/恢复；不要手改版本号 |
| 校验和不符 / 文件截断 / JSON 损坏 | 拒绝恢复，状态不变 | 重新导出；传输后先比对大小/校验 |
| `windowMillis` 与运行实例不一致 | 拒绝恢复，状态不变 | 用相同窗口配置启动，或在同配置实例间迁移 |
| 审计断号、`lastSeq` 与条数不一致、空字段 | 拒绝恢复，状态不变 | 快照不可信，重新导出 |
| 导出写盘失败 | 抛错，已有快照文件原样保留 | 检查目录权限/磁盘空间，临时文件可手动清理 |

> 注意：恢复是**整体替换**语义（快照即事实来源），恢复点之后产生但不在快照内的内存状态
> 不会保留。请在明确的切换点操作；生产环境应将本地文件替换为带复制/共识的共享存储。

---

## 7. HTTP 接口

### 创建订单（受治理写接口）

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H 'X-Tenant-Id: tenant-a' -H 'X-Role: WRITER' -H 'X-Request-Id: demo-1' \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant-a","idempotencyKey":"k-1001","payload":"buy-1-item"}'
```

响应（首次）：

```json
{ "orderId": "ORD-tenant-a-xxxx", "replayed": false, "auditSeq": 1, "requestId": "demo-1" }
```

再次用**相同 body + 相同幂等键**调用：`replayed=true`，`orderId` 不变，副作用不重复。
将 `payload` 改为不同内容：返回 `409 IDEMPOTENCY_CONFLICT`。

### 运行期调整配额（立即生效，保留计数）

```bash
curl -s -X POST http://localhost:8080/api/admin/quota \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant-a","action":"CREATE_ORDER","limit":100}'
```

### 查看审计流水

```bash
curl -s http://localhost:8080/api/admin/audit
```

### 导出 / 恢复治理快照

```bash
# 导出（默认 governance-snapshot.snap，也可用 path 指定）
curl -s -X POST 'http://localhost:8080/api/admin/snapshots/export?path=/tmp/gov.snap'

# 重启进程后（或运行期）整体接回
curl -s -X POST 'http://localhost:8080/api/admin/snapshots/restore?path=/tmp/gov.snap'

# 坏快照 -> 400
# {"error":"SNAPSHOT_REJECTED","reason":"INVALID_SNAPSHOT",
#  "message":"snapshot checksum mismatch: file is corrupted or truncated; restore rejected", ...}
```

### 典型拒绝

```bash
# 越权：READER 写 -> 403 FORBIDDEN_ROLE
# 跨租户：tenant-a 的 WRITER 操作 tenant-b -> 403 CROSS_TENANT_ACCESS
curl -i -X POST http://localhost:8080/api/orders \
  -H 'X-Tenant-Id: tenant-a' -H 'X-Role: WRITER' \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant-b","idempotencyKey":"k-9","payload":"x"}'

# 限流：窗口内超过 3 次 -> 429 QUOTA_EXCEEDED
```

HTTP 状态码映射：`400`（快照版本不符 / 损坏 / 校验失败 `SNAPSHOT_REJECTED`）、
`403`（UNKNOWN_TENANT / CROSS_TENANT_ACCESS / FORBIDDEN_ROLE）、
`429`（QUOTA_EXCEEDED）、`409`（IDEMPOTENCY_CONFLICT）。

---

## 8. 本地验证方法

```bash
# 运行全部单元/并发/集成测试（26 个用例）
mvn test

# 只跑某一类场景
mvn -Dtest=IdempotencyConcurrencyTest test   # 并发重复提交 / 冲突
mvn -Dtest=QuotaBoundaryTest test            # 配额边界 / 隔离 / 热更新 / 窗口滚动
mvn -Dtest=AccessControlTest test            # 跨租户 / 角色越权
mvn -Dtest=AuditOrderingTest test            # 审计单调递增
mvn -Dtest=SnapshotGovernanceTest test       # 快照导出/恢复、并发一致性、坏快照拒绝、大状态延迟
mvn -Dtest=SnapshotHttpIntegrationTest test  # 快照运维接口 HTTP 端到端（含坏快照 400）
mvn -Dtest=HttpGovernanceIntegrationTest test # HTTP 端到端 + 状态码
```

`SnapshotGovernanceTest` 覆盖：

1. `exportThenRestoreKeepsQuotaIdempotencyAndAuditContinuity`：导出后用**全新组件**模拟重启、
   再恢复——窗口计数接回（再放 1 个即满、随后限流）、幂等返回首次订单号且无重复副作用、
   审计从 `lastSeq+1` 连续；
2. `expiredWindowCountIsCollapsedOnRestore`：恢复时已过期窗口的计数折叠为 0、上限保留；
3. `corruptedOrIncompatibleSnapshotsAreRejectedAndStateUntouched`：篡改 payload、截断、
   魔数版本不符、payload `formatVersion` 不符全部被拒，拒绝后计数/幂等/审计序号原样；
4. `incompatibleWindowMillisIsRejected`：窗口长度不一致拒绝且不写入任何状态；
5. `failedWriteDoesNotDestroyExistingSnapshot`：导出写盘失败时旧快照字节级保留、仍可恢复；
6. `concurrentExportsSeeConsistentStateAndQuotaConclusionsStable`：24 写线程 + 6 导出线程
   并发，每份导出都能被独立世界校验通过（自洽一致），配额守恒、审计 1..N 不断号；
7. `concurrentRestoreIsAllOrNothingAndAuditContinues`：恢复与在线请求并发、重复 5 轮，
   恢复恰好生效一次、幂等可用、最终审计序号严格连续；
8. `largeRestoreKeepsOnlineDecisionLatencyLow`：1.2 万历史记录恢复后，在线判定平均/p95
   延迟仍在低位（打印实测值）。

测试使用可控时钟（`QuotaService(windowMillis, clock)`）保证限流结果**稳定可复现**，
不依赖真实时间流逝；并发用例通过 `CountDownLatch` 统一发令、断言精确数量，结果确定。
全套用例连续多轮运行结果一致（已验证 3 轮全绿），不会时对时错。

单测日志会打印**请求标识、判定依据与审计序号**，例如：

```
[req-pre-1] seq=1 decision=ACCEPT basis=quota ok (1/3), order created
[req-post-replay] decision=REPLAY orderId=ORD-tenant-a-xxxx auditSeq=5 basis=restored idempotent result
[req-post-over] rejected reason=QUOTA_EXCEEDED basis=quota exhausted ... auditSeq=5
[export-0] consistent snapshot quotas=2 idem=3 audit=4
[reject-tampered.snap] refused: snapshot checksum mismatch: file is corrupted or truncated; restore rejected
[large-restore] restore=84ms onlineDecision avg=12.3us p95=40us (history=12000)
```

> 说明：当前为**单进程内存态 + 本地快照文件**实现：快照解决了重启 / 换环境接回与一致切换；
> 跨进程的实时共享仍需将配额、幂等记录、审计替换为 Redis / 数据库等共享存储，
> 治理判定顺序、原因枚举与快照版本/校验机制可直接复用。
