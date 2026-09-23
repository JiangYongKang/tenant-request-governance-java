# 多租户请求治理服务（tenant-request-governance-java）

基于 Spring Boot 3 / Java 21 的多租户请求治理能力演示。所有状态（配额、幂等、审计）
均使用**本地内存**模拟，但在并发下提供一致且可解释的结论：

- 按 **租户 + 操作** 维度的固定窗口配额限流，并发计数准确；
- 写操作 **幂等提交**，重复提交返回首次结果，业务副作用只发生一次；
- **租户 + 角色** 访问控制，越权 / 跨租户被拒绝且原因可区分；
- 每个被接受或被拒绝的关键操作都留下**单调递增、只追加**的审计记录。

---

## 1. 快速开始

```bash
# 构建 + 全部单测（17 个用例）
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
- **配额与幂等的关系**：只有真正“首次执行业务”的请求才消耗配额；
  幂等重放与内容冲突在判定后会**回退预占**，不会因重复提交耗尽窗口。

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

## 6. HTTP 接口

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
mvn -Dtest=QuotaBoundaryTest test            # 配额边界 / 隔离 / 热更新 / 窗口滚动
mvn -Dtest=AccessControlTest test            # 跨租户 / 角色越权
mvn -Dtest=AuditOrderingTest test            # 审计单调递增
mvn -Dtest=HttpGovernanceIntegrationTest test # HTTP 端到端 + 状态码
```

测试使用可控时钟（`QuotaService(windowMillis, clock)`）保证限流结果**稳定可复现**，
不依赖真实时间流逝；并发用例通过 `CountDownLatch` 统一发令、断言精确数量，结果确定。

单测日志会打印**请求标识、判定依据与审计序号**，例如：

```
[req-concurrent-7] decision=ACCEPT auditSeq=1 basis=quota ok (1/10000), order created
[req-concurrent-8] decision=REPLAY auditSeq=2 basis=idempotent replay of key key-hot (quota not consumed)
[req-bound-over] rejected reason=QUOTA_EXCEEDED basis=quota exhausted ... auditSeq=4
```

> 说明：当前实现为**单进程内存态**，重启后状态丢失，仅用于演示与本地验证；
> 生产环境应将配额、幂等记录、审计替换为 Redis / 数据库等共享存储，治理判定顺序与原因枚举可直接复用。
