# 多租户请求治理服务（tenant-request-governance-java）

基于 Spring Boot 3 / Java 21 的多租户请求治理能力演示。所有状态（配额、幂等、审计）
均使用**本地内存**模拟，但在并发下提供一致且可解释的结论；同时支持把治理状态
**整体导出为本地快照文件并在需要时接回**，重启或换环境后限流、幂等、审计接着走：

- 按 **租户 + 操作** 维度的固定窗口配额限流，并发计数准确；
- 写操作 **幂等提交**，重复提交返回首次结果，业务副作用只发生一次；
- **租户 + 角色** 访问控制，越权 / 跨租户被拒绝且原因可区分；
- 每个被接受或被拒绝的关键操作都留下**单调递增、只追加**的审计记录；
- 治理状态**快照导出 / 恢复**：并发安全、版本化、损坏即拒、恢复原子生效。

---

## 1. 快速开始

```bash
# 构建 + 全部单测（29 个用例）
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
- **配额与幂等的关系**：只有真正“首次执行业务”的请求才消耗配额。判定顺序为
  **访问控制 → 幂等占位判定（不碰配额）→ 仅首次提交占配额 → 执行业务**：
  - 幂等重放（无论窗口是否还有余量、是否已经打满、状态是否来自快照接回）直接返回首次结果，
    **不占、不退、不等待配额**，连续重放也不会改动窗口已用计数；
  - 同键不同内容的冲突在占配额之前就以 `409` 拒绝，不算放行、不占计数；
  - 首次提交撞上限流才返回 `429` 并放开幂等占位，窗口滚动或配额恢复后同一键仍可重新首次提交。
  见 `IdempotencyReplayQuotaTest`（窗口打满重放 / 快照接回后重放 / 与首次提交并发 / 同键冲突）。

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
- **首次提交**：原子抢到该键的首位（leader）后才去占窗口配额并执行业务，
  产生一次真实副作用（`OrderService` 内部计数 +1），结果固化后唤醒所有等待者。
- **重复提交（相同内容）**：返回**首次结果**，`replayed=true`，业务副作用**不再次发生**。
  幂等判定发生在配额占用**之前**，因此：
  - **窗口打满后重放**：仍原样返回首次订单结果，不会被 `429 QUOTA_EXCEEDED` 挡住；
  - **不占配额**：无论连续重放多少次，窗口已用计数既不多算也不少算（不需要“先占再退”）；
  - **与首次提交并发**：后到线程合流等待进行中的首次提交，完成后稳定拿到同一个首次结果；
  - **快照接回后重放**：接回的首次结果以“已完成”形态放回，重启 / 运行期恢复后上述行为完全一致。
- **同键不同内容**：对请求体做 SHA-256 摘要比对，不一致立即返回
  `409 IDEMPOTENCY_CONFLICT`，明确拒绝（区别于正常重放）。
  冲突判定同样先于配额：**窗口打满、快照接回之后，同键不同内容仍是 409**，
  不会被当成重放返回旧结果，也不会算作一次放行。
- **并发重复提交**：使用 `ConcurrentHashMap.compute` 原子地“判占位 + 放占位”，
  后到线程通过 `CompletableFuture` **合流等待**首个请求，因此任意并发度下副作用恰好一次
  （见 `IdempotencyConcurrencyTest#concurrentDuplicatesProduceSingleSideEffect`：
  32 并发、同一幂等键，唯一订单数 = 1、副作用次数 = 1、其余全部 REPLAY；
  首次提交“进行中”时刻的合流见
  `IdempotencyReplayQuotaTest#concurrentReplayJoinsInFlightFirstSubmission`）。
- 首次提交在占配额前无需回滚；占配额后业务失败或超限被拒时会回退预占并移除占位，
  允许后续用相同键重试（见 `IdempotencyReplayQuotaTest#rejectedFirstSubmissionReleasesSlotAndCanRetry`）。

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

## 6. 状态快照（导出 / 恢复）

治理状态可以整体导出为一份本地快照文件，服务重启或运行期重新加载后**接着用**，
而不是回到初始值。

### 快照包含什么

| 状态 | 内容 | 恢复后的行为 |
|------|------|--------------|
| 限流 | 每个 `(tenant, action)` 的配额上限、窗口起点、窗口内已用计数 | 未过期窗口的已用计数继续算进限流；过期窗口按既有滚动逻辑自然归零 |
| 幂等 | 已生效的 `(tenant, idempotencyKey)` 首次提交结果（含请求摘要） | 同租户同键重复提交仍返回首次结果，业务副作用不再发生；即使恢复后窗口已打满，重放也不被限流挡住、不占计数；同键不同内容仍报 `409 IDEMPOTENCY_CONFLICT` |
| 审计 | 全部审计流水 + 已用最大序号 | 新记录从已有序号往后接着排，不重复、不回退、不错序 |

### 并发与一致性语义

- **导出**：全局写锁内只做一次纯内存拷贝（微秒级），序列化与落盘在锁外完成。
  在线请求只被短暂阻塞，导出的内容是**某一时刻的一致状态**，不会拼出一半旧值一半新值
  （见 `SnapshotPersistenceTest#concurrentExportYieldsConsistentSnapshot`：请求洪峰中导出的
  每份快照，审计条数 / ACCEPT 数 / 幂等记录数 / 配额计数互相吻合）。
- **恢复**：版本、校验和、结构、审计序号连续性、幂等结果可反序列化等**全部校验在加锁前完成**；
  校验通过后才在写锁内一次性换入三类状态——**要么整体生效、要么整体不生效**，
  不存在恢复了一半的中间态（见 `concurrentRestoreIsAtomicAndRequestsSeeOldOrNewNotMixed`）。
- **落盘**：先写同目录临时文件再原子 rename，快照写到一半失败不会留下半个正式文件；
  文件首行为元信息（版本 / 时间戳 / SHA-256 校验和），校验和覆盖载荷字节，截断或篡改必被发现。
- **性能**：恢复后各类判定仍是 `ConcurrentHashMap` 的 O(1) 查找，
  大快照（2 万条幂等记录）恢复后在线判定耗时不明显劣化
  （见 `largeSnapshotRestoreKeepsDecisionsFast`）。

### 版本兼容

- 快照携带 `formatVersion`（当前为 **1**），只接受**完全一致**的版本号；
  版本不符、校验和不匹配、内容损坏、结构非法一律抛出 `SnapshotException`
  （HTTP 层映射为 `400 SNAPSHOT_INVALID`），**当前在线状态不被污染或覆盖**，
  拒绝后服务按原状态继续限流 / 幂等 / 审计
  （见 `corruptedTruncatedAndIncompatibleSnapshotsAreRejectedWithoutPollutingState`）。

### 怎么用

```bash
# 导出（path 缺省为 ./governance-snapshot.json）
curl -s -X POST http://localhost:8080/api/admin/snapshot/export \
  -H 'Content-Type: application/json' -d '{"path":"/tmp/gov-snap.json"}'

# 恢复（运行期整体换入；重启后调用同一接口即可接回）
curl -s -X POST http://localhost:8080/api/admin/snapshot/restore \
  -H 'Content-Type: application/json' -d '{"path":"/tmp/gov-snap.json"}'
```

失败处理：恢复被拒绝时响应体携带 `SNAPSHOT_INVALID` 与具体原因
（版本不兼容 / 校验和不匹配 / 文件不可读等），在线状态保持原样，
修正快照文件后重试即可；导出失败（如目录不可写）同样只报错、不影响在线状态。

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

> 窗口打满后（tenant-a 配额 3，已成功 3 笔），用已生效幂等键重放仍返回
> `replayed=true` 的首次订单，而不是 `429`；换新幂等键的首次提交才返回 `429`。
> 先 `POST /api/admin/snapshot/export` 导出、重启后 `restore` 接回，结论相同。

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

HTTP 状态码映射：`403`（UNKNOWN_TENANT / CROSS_TENANT_ACCESS / FORBIDDEN_ROLE）、
`429`（QUOTA_EXCEEDED）、`409`（IDEMPOTENCY_CONFLICT）。

---

## 7. 本地验证方法

```bash
# 运行全部单元/并发/集成测试
mvn test

# 只跑某一类场景
mvn -Dtest=IdempotencyConcurrencyTest test   # 并发重复提交 / 冲突
mvn -Dtest=IdempotencyReplayQuotaTest test   # 窗口打满/快照接回后重放、重放并发合流、同键冲突
mvn -Dtest=QuotaBoundaryTest test            # 配额边界 / 隔离 / 热更新 / 窗口滚动
mvn -Dtest=AccessControlTest test            # 跨租户 / 角色越权
mvn -Dtest=AuditOrderingTest test            # 审计单调递增
mvn -Dtest=HttpGovernanceIntegrationTest test # HTTP 端到端 + 状态码
mvn -Dtest=SnapshotPersistenceTest test      # 快照导出/恢复/并发一致性/拒绝损坏快照
mvn -Dtest=HttpSnapshotIntegrationTest test  # 快照 HTTP 端点端到端

# 反复执行验证并发用例稳定（结果一致、无偶发失败）
for i in $(seq 1 20); do mvn -Dtest=IdempotencyReplayQuotaTest test | grep 'Tests run:'; done
```

测试使用可控时钟（`QuotaService(windowMillis, clock)`）保证限流结果**稳定可复现**，
不依赖真实时间流逝；并发用例通过 `CountDownLatch` 统一发令、断言精确数量，结果确定。
快照用例额外覆盖：并发导出时快照内部三类状态互相吻合、并发恢复时请求只见完整旧态或完整新态、
损坏/截断/版本不符的快照被拒绝且在线状态分毫未动、大快照恢复后判定耗时不劣化。

单测日志会打印**请求标识、判定依据与审计序号**，例如：

```
[req-concurrent-7] decision=ACCEPT auditSeq=1 basis=quota ok (1/10000), order created
[req-concurrent-8] decision=REPLAY auditSeq=2 basis=idempotent replay of key key-hot (quota not consumed)
[req-bound-over] rejected reason=QUOTA_EXCEEDED basis=quota exhausted ... auditSeq=4
[req-replay-0] decision=REPLAY auditSeq=5 basis=idempotent replay of key key-original (quota not consumed) windowUsed=3
[req-full-over] decision=REJECT reason=QUOTA_EXCEEDED auditSeq=6 basis=quota exhausted ... (used 3/3 in window) windowUsed=3
[req-cf-diff] decision=REJECT reason=IDEMPOTENCY_CONFLICT auditSeq=3 basis=...different request body windowUsed=2
[snapshot] snap-2.json consistent: entries=217 accepts=217
[snapshot] corrupted rejected: corrupted snapshot: checksum mismatch
```

> 说明：运行态仍是**单进程内存实现**，但治理状态可通过快照导出/恢复在重启间接续；
> 生产环境应将配额、幂等记录、审计替换为 Redis / 数据库等共享存储，治理判定顺序与原因枚举可直接复用。
