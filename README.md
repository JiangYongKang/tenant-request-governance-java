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
# 构建 + 全部单测（28 个用例）
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
- **配额与幂等的先后关系**：治理链路**先判幂等、再占配额**。只有幂等判定为“首次提交”的请求
  才会进入配额占用；幂等重放（同租户同键同内容）与内容冲突在判定环节就已定案，
  **全程不触碰窗口计数**——不是“先预占再回退”，因此窗口计数既不会被重放/冲突多算，
  也不存在回退时序造成的少算。详见第 3 节。

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
- **判定先于配额**：请求通过访问控制后，先在幂等层原子地“判占位 / 放占位”，再决定要不要占配额：
  - **首次提交（leader）**：占位后去占窗口配额，配额通过才执行业务，产生一次真实副作用
    （`OrderService` 内部计数 +1），成功后发布结果并缓存；配额超限则立即撤位并按
    `429 QUOTA_EXCEEDED` 拒绝，业务不执行。
  - **重复提交 / 重放（waiter）**：返回**首次结果**，`replayed=true`，业务副作用**不再次发生**。
- **重放不占限流配额，也不会被限流挡住**：重放路径完全不触碰配额窗口，所以
  - 窗口**还有余量**时重放：不增加已用计数；
  - 窗口**已经打满**时重放：依旧原样返回第一次的结果（不会被 `QUOTA_EXCEEDED` 拒掉），
    连续多次重放计数纹丝不动；而真正的首次提交（新键）仍照常按配额超限拒绝；
  - **服务重启 / 运行期接回快照之后**：已生效的幂等结果随快照接回，窗口已满时重放表现完全一致，
    仍返回首次结果、不占配额、不产生第二次副作用。
- **重放与进行中的首次提交并发**：同键并发请求在原子占位环节成为 waiter，通过
  `CompletableFuture` **合流等待**首个请求；任意并发度下副作用恰好一次，等待中的重放同样
  不占配额，窗口的已用计数**既不被多算、也不被少算**
  （见 `ReplayQuotaBoundaryTest#replayConcurrentWithInFlightFirstSubmissionJoinsAndDoesNotConsumeQuota`：
  窗口配额为 1，首次提交在业务中途挂住时 8 个并发重放全部等待、计数恒为 1/1，放行后全部拿到首次结果）。
- **同键不同内容**：对请求体做 SHA-256 摘要比对，不一致立即返回
  `409 IDEMPOTENCY_CONFLICT`，明确拒绝（区别于正常重放，也区别于限流）；冲突路径同样不占配额、
  不算一次正常放行。该边界在**窗口打满时**与**从快照接回状态之后**都保持成立
  （见 `ReplayQuotaBoundaryTest#sameKeyDifferentBodyConflictsEvenWhenWindowExhausted`
  与 `#replayAndConflictAfterSnapshotRestoreWhenWindowExhausted`）。
- **首次提交失败**：业务异常或配额超限时移除占位、回退本次预占（仅 leader 会预占），
  相同键之后允许重新作为首次提交；已在等待的合流者按同一原因解套，不会拿到半成品结果。
- **并发重复提交合流**：使用 `ConcurrentHashMap.compute` 原子地“判占位 + 放占位”，
  后到线程合流等待首个请求，因此任意并发度下副作用恰好一次
  （见 `IdempotencyConcurrencyTest#concurrentDuplicatesProduceSingleSideEffect`：
  32 并发、同一幂等键，唯一订单数 = 1、副作用次数 = 1、其余全部 REPLAY）。

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
| 幂等 | 已生效的 `(tenant, idempotencyKey)` 首次提交结果（含请求摘要） | 同租户同键重复提交仍返回首次结果，业务副作用不再发生；**即使接回时窗口已打满，重放也不占配额、不被限流**；同键不同内容仍报 `409 IDEMPOTENCY_CONFLICT` |
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

再次用**相同 body + 相同幂等键**调用：`replayed=true`，`orderId` 不变，副作用不重复；
**窗口打满之后（或接回快照之后）再重放也仍然成立**，重放不占配额、不会收到 429。
将 `payload` 改为不同内容：返回 `409 IDEMPOTENCY_CONFLICT`，窗口满时同样如此。

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

## 8. 本地验证方法

```bash
# 运行全部单元/并发/集成测试
mvn test

# 只跑某一类场景
mvn -Dtest=IdempotencyConcurrencyTest test   # 并发重复提交 / 冲突
mvn -Dtest=QuotaBoundaryTest test            # 配额边界 / 隔离 / 热更新 / 窗口滚动
mvn -Dtest=ReplayQuotaBoundaryTest test      # 窗口打满后重放（含快照接回）/ 重放并发首次提交 / 满窗口冲突
mvn -Dtest=AccessControlTest test            # 跨租户 / 角色越权
mvn -Dtest=AuditOrderingTest test            # 审计单调递增
mvn -Dtest=HttpGovernanceIntegrationTest test # HTTP 端到端 + 状态码
mvn -Dtest=SnapshotPersistenceTest test      # 快照导出/恢复/并发一致性/拒绝损坏快照
mvn -Dtest=HttpSnapshotIntegrationTest test  # 快照 HTTP 端点端到端
```

**专门验证本次边界（窗口打满后的幂等重放）**：

```bash
# 只跑边界用例；其中并发轮次重复 5 轮，可多跑几次确认稳定
mvn -Dtest=ReplayQuotaBoundaryTest test
```

覆盖四个可稳定复现的场景（固定时钟 + `CountDownLatch` 发令，不依赖真实时间）：

1. `replayAfterWindowExhaustedReturnsFirstResultWithoutConsumingQuota`：
   窗口打满（2/2）后同键同内容连续重放 3 次，始终返回第一次的订单结果，已用计数恒为 2，
   副作用只有一次；期间新键首次提交仍被 `429` 拒绝。
2. `replayAndConflictAfterSnapshotRestoreWhenWindowExhausted`：
   导出“窗口已打满 + 已有幂等结果”的快照，重启新实例并接回后，重放仍返回首次结果、
   无新副作用、计数不变；同键不同内容仍报 `409`；新键首次提交仍被 `429`。
3. `replayConcurrentWithInFlightFirstSubmissionJoinsAndDoesNotConsumeQuota`：
   配额为 1，首次提交在业务中途挂住（已占 1/1），8 个并发重放在静默期内既不提前返回也不被拒绝、
   计数恒为 1；放行后全部合流到首次结果，完成后再连续重放结论不变（自动重复 5 轮）。
4. `sameKeyDifferentBodyConflictsEvenWhenWindowExhausted`：
   满窗口下同键不同内容报可区分的 `409 IDEMPOTENCY_CONFLICT`，既不返回旧结果也不算放行，
   且不会污染首次记录（之后相同内容仍可正常重放）。

也可以直接用 curl 手工验证窗口打满后的重放（tenant-a 窗口配额为 3）：

```bash
# 用 key-1 打满后再重放同一幂等键：即使窗口已用 3/3，重放仍 200 且 orderId 不变
for k in k-fill-1 k-fill-2 k-fill-3; do
  curl -s -X POST http://localhost:8080/api/orders \
    -H 'X-Tenant-Id: tenant-a' -H 'X-Role: WRITER' -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"tenant-a\",\"idempotencyKey\":\"$k\",\"payload\":\"p\"}"; echo
done
curl -s -X POST http://localhost:8080/api/orders \
  -H 'X-Tenant-Id: tenant-a' -H 'X-Role: WRITER' -H 'X-Request-Id: replay-after-full' \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant-a","idempotencyKey":"k-fill-1","payload":"p"}'; echo   # 200 replayed=true
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
[req-replay-1] decision=REPLAY auditSeq=5 basis=idempotent replay of key key-1 (quota not consumed) windowUsed=2
[req-post-conflict] rejected reason=IDEMPOTENCY_CONFLICT basis=...different request body auditSeq=6 windowUsed=1
[concurrent-round-0] leader=1 replays=8 joined sideEffects=1 windowUsed=1/1
[snapshot] snap-2.json consistent: entries=217 accepts=217
[snapshot] corrupted rejected: corrupted snapshot: checksum mismatch
```

> 说明：运行态仍是**单进程内存实现**，但治理状态可通过快照导出/恢复在重启间接续；
> 生产环境应将配额、幂等记录、审计替换为 Redis / 数据库等共享存储，治理判定顺序与原因枚举可直接复用。
