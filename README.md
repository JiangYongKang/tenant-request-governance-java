# tenant-request-governance-java-bootstrap

基于 Spring Boot 3.4 / Java 21 的**多租户请求治理**示例服务。限流、幂等、访问控制与审计全部使用
本地内存状态实现，不依赖外部中间件，重点保证并发下结论一致、可解释、可复现。

## 能力总览

治理流水线（`GovernanceService`）按固定顺序执行，每一步的接受/拒绝都会写一条审计记录：

```
请求 → ① 访问控制 → ② 配额限流 → ③ 幂等执行 → ④ 审计留痕
```

| 判定 (`Decision`)        | 含义 |
|--------------------------|------|
| `ALLOWED`                | 接受并执行（首次提交） |
| `IDEMPOTENT_REPLAY`      | 幂等重放，返回首次结果，无新副作用 |
| `IDEMPOTENCY_CONFLICT`   | 同一幂等键携带了不同请求内容 |
| `QUOTA_EXCEEDED`         | 租户+操作维度的配额超限 |
| `ROLE_FORBIDDEN`         | 角色无权执行该操作 |
| `TENANT_FORBIDDEN`       | 跨租户访问被拒绝 |
| `UNKNOWN_TENANT`         | 发起租户不存在 |

拒绝原因相互可区分，`detail` 字段给出判定依据（命中的配额上限、已用计数等）。

## 1. 限流（配额）语义

- 维度：`(tenantId, operation)` 的**固定时间窗口**计数（默认窗口 60s）。
- 并发准确：每个 key 持有独立窗口对象与锁，窗口滚动与计数自增在同一把锁内完成；
  并发压测中“接受数”精确等于配额（如 200 并发 / 配额 50 → 恰好 50 接受、150 拒绝）。
- 租户隔离：不同 `(租户, 操作)` 的计数器与锁完全独立，单租户把自己的配额打爆
  **不阻塞、不影响**其它租户的可用性。
- 超限请求被拒绝，且**不计入**已用计数；“已用计数”精确等于窗口内被接受的请求数。
- 运行期调额 `PUT /governance/quotas/{tenant}/{operation}?limit=N` **立即生效**：
  配额上限与计数器分开存储，调额只改上限，已有计数不丢失、不重置、不串用。
- 窗口到期后计数归零，配额重新可用（`QuotaWindowTest` 覆盖）。
- 未显式配置时使用默认上限 1000/窗口。

## 2. 幂等提交语义

- 幂等键以**租户为命名空间**：`(tenantId, idempotencyKey)` 唯一，不同租户的相同键互不影响。
- 首次提交：在该键的条目锁内执行业务副作用并保存结果，副作用**全进程最多一次**。
- 并发重复提交：同键请求在同一把条目锁上排队，首个完成后其余全部拿到**同一个首次结果**
  （`IDEMPOTENT_REPLAY`），不产生重复副作用；不同键之间互不阻塞。
- 同键不同内容：锁内比较首次请求内容，不一致则返回 `IDEMPOTENCY_CONFLICT`，不产生副作用。
- 无幂等键的请求（如读操作）直接执行，不走幂等存储。

## 3. 租户隔离与访问规则

预置租户：`tenant-a` / `tenant-b` / `tenant-c`；角色：`ADMIN` / `WRITER` / `READER`。

1. `tenantId` 不存在 → `UNKNOWN_TENANT`。
2. `targetTenantId ≠ tenantId`（跨租户访问）→ `TENANT_FORBIDDEN`；仅 `ADMIN` 允许跨租户。
3. 角色校验：
   - `READER` 仅允许操作名以 `.read` / `.query` 结尾的读操作，写操作 → `ROLE_FORBIDDEN`；
   - `WRITER` 允许写与读；
   - `ADMIN` 允许全部操作并可跨租户。
   - 未知角色 → `ROLE_FORBIDDEN`。

## 4. 审计

- 每个被**接受或被拒绝**的关键操作都追加一条不可变记录（`AuditEntry`）。
- 审计序号由全局 `AtomicLong` 分配，且**序号分配与列表追加在同一把锁内完成**，
  保证序号从 1 开始连续、单调递增，并发下不会重复、错序或覆盖。
- 记录只追加，无修改/删除入口；`GET /governance/audit` 按序号升序返回快照。

## HTTP 接口

```bash
# 提交受治理请求
curl -s -X POST localhost:8080/governance/requests \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"req-1","tenantId":"tenant-a","targetTenantId":"tenant-a",
       "role":"WRITER","operation":"order.create","idempotencyKey":"k-1","payload":"p1"}'

# 运行期调整配额（立即生效）
curl -s -X PUT 'localhost:8080/governance/quotas/tenant-a/order.create?limit=5'

# 查看审计（序号升序）
curl -s localhost:8080/governance/audit
```

响应示例（字段：判定、判定依据、审计序号、业务结果、剩余配额）：

```json
{ "requestId": "req-1", "decision": "ALLOWED",
  "detail": "first submission, effect executed once",
  "auditSeq": 1, "body": "effect:order.create#1", "quotaRemaining": 4 }
```

## 本地验证

```bash
# 运行全部测试（含并发重复提交、配额边界、跨租户越权、窗口滚动、HTTP 冒烟）
mvn test

# 启动服务
mvn spring-boot:run
```

测试清单：

| 测试类 | 覆盖点 |
|--------|--------|
| `IdempotencyConcurrencyTest` | 32 线程同键并发 → 恰好 1 次执行、31 次重放、副作用=1；同键不同内容冲突；跨租户同键独立 |
| `QuotaBoundaryTest` | 配额边界精确；200 并发/配额 50 计数准确；噪声租户不影响他人；运行期调额立即生效且保留计数 |
| `QuotaWindowTest` | 固定窗口滚动后计数归零；租户/操作维度计数不串用 |
| `AccessControlAndAuditTest` | 三类拒绝原因可区分；READER/ADMIN 规则；64 并发下审计序号 1..N 连续单调、接受与拒绝均留痕 |
| `GovernanceControllerIntegrationTest` | REST 提交/重放/调额/审计端到端 |

单测日志通过 SLF4J 打印每个请求的 `requestId`、`decision`、判定依据（`basis`）、
`auditSeq`、剩余配额与副作用序号，审计日志以 `[audit#序号]` 前缀输出，便于逐条追溯。

> 说明：本服务为演示用途，状态保存在单实例内存中，重启即丢失；
> 生产环境应将配额、幂等记录与审计替换为 Redis / 数据库等共享存储。
