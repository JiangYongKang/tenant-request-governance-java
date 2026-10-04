package com.github.highcumontoa.tenantrequestgovernancejava.governance;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.business.OrderService;
import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RequestContext;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotentResult;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 请求治理编排门面：访问控制 -> 配额限流 -> 幂等执行 -> 审计留痕。
 * <p>每个被接受或被拒绝的关键操作都产生一条审计记录，序号单调递增。
 */
public class GovernanceFacade {

    /** 受治理的操作名，同时作为配额维度的 action。 */
    public static final String ACTION_CREATE_ORDER = "CREATE_ORDER";

    private final AccessControlService accessControl;
    private final QuotaService quota;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final OrderService orders;
    private final GovernanceStateLock stateLock;

    public GovernanceFacade(AccessControlService accessControl, QuotaService quota,
                            IdempotencyService idempotency, AuditService audit, OrderService orders) {
        this(accessControl, quota, idempotency, audit, orders, new GovernanceStateLock());
    }

    public GovernanceFacade(AccessControlService accessControl, QuotaService quota,
                            IdempotencyService idempotency, AuditService audit, OrderService orders,
                            GovernanceStateLock stateLock) {
        this.accessControl = accessControl;
        this.quota = quota;
        this.idempotency = idempotency;
        this.audit = audit;
        this.orders = orders;
        this.stateLock = stateLock;
    }

    /**
     * 写操作：受访问控制 + 配额 + 幂等保护，全程审计。
     * 整个判定-执行-留痕在全局读锁内进行：请求之间彼此并发，
     * 快照导出/恢复（写锁）开始时不会有“执行到一半”的请求，保证快照是某一时刻的一致状态。
     */
    public GovernanceResult<String> createOrder(RequestContext ctx, String idempotencyKey, String payload) {
        return stateLock.withReadLock(() -> doCreateOrder(ctx, idempotencyKey, payload));
    }

    private GovernanceResult<String> doCreateOrder(RequestContext ctx, String idempotencyKey, String payload) {
        // 1) 访问控制：未注册租户 / 角色越权 / 跨租户 -> 审计后拒绝。
        try {
            accessControl.check(ctx, ACTION_CREATE_ORDER);
        } catch (GovernanceException ex) {
            auditReject(ctx, ex);
            throw ex;
        }

        // 2) 配额限流：超限 -> 审计后拒绝，原因可区分且携带已用/上限。
        QuotaService.AcquireOutcome outcome = quota.tryAcquire(ctx.tenantId(), ACTION_CREATE_ORDER);
        if (!outcome.allowed()) {
            String detail;
            if (outcome.limit() < 0) {
                detail = "no quota configured for tenant " + ctx.tenantId();
            } else {
                detail = "quota exhausted for tenant " + ctx.tenantId()
                        + " (used " + outcome.used() + "/" + outcome.limit() + " in window)";
            }
            GovernanceException ex = new GovernanceException(RejectReason.QUOTA_EXCEEDED, detail);
            auditReject(ctx, ex);
            throw ex;
        }

        String resourceTenant = ctx.resourceTenantId() == null ? ctx.tenantId() : ctx.resourceTenantId();
        String requestHash = sha256(payload);
        try {
            // 3) 幂等执行：副作用仅在首次提交发生一次；并发重复提交合流。
            IdempotentResult<String> result = idempotency.execute(
                    ctx.tenantId(), idempotencyKey, requestHash,
                    () -> orders.createOrder(resourceTenant, payload));

            // 重放（重复提交）不重复消耗业务配额：回退本次预占，仅首次提交计数。
            if (result.replayed()) {
                quota.release(ctx.tenantId(), ACTION_CREATE_ORDER);
            }
            AuditEntry entry = audit.record(
                    ctx.requestId(), ctx.tenantId(), ACTION_CREATE_ORDER,
                    result.replayed() ? "REPLAY" : "ACCEPT",
                    result.replayed()
                            ? "idempotent replay of key " + idempotencyKey + " (quota not consumed)"
                            : "quota ok (" + outcome.used() + "/" + outcome.limit()
                                    + "), order created");
            return new GovernanceResult<>(result.value(), result.replayed(), entry.seq());
        } catch (GovernanceException ex) {
            // 幂等冲突：请求未被真正处理，回退预占配额；拒绝也必须留痕。
            if (ex.getReason() == RejectReason.IDEMPOTENCY_CONFLICT) {
                quota.release(ctx.tenantId(), ACTION_CREATE_ORDER);
            }
            auditReject(ctx, ex);
            throw ex;
        }
    }

    private void auditReject(RequestContext ctx, GovernanceException ex) {
        audit.record(ctx.requestId(), ctx.tenantId(), ACTION_CREATE_ORDER, "REJECT",
                ex.getReason().name() + ": " + ex.getMessage());
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input == null ? new byte[0] : input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
