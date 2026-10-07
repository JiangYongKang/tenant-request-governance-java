package com.github.highcumontoa.tenantrequestgovernancejava.governance;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.business.OrderService;
import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RequestContext;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.Admission;
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

        String resourceTenant = ctx.resourceTenantId() == null ? ctx.tenantId() : ctx.resourceTenantId();
        String requestHash = sha256(payload);

        // 2) 幂等占位判定（不消耗任何配额）：
        //    重放（含窗口已打满、快照接回之后）在此直接拿到首次结果；
        //    同键不同内容在此直接冲突拒绝；只有抢到首位的请求才继续往下占配额。
        Admission admission;
        try {
            admission = idempotency.admit(ctx.tenantId(), idempotencyKey, requestHash);
        } catch (GovernanceException ex) {
            // 幂等冲突：请求未被真正处理，也从未触碰配额；拒绝也必须留痕。
            auditReject(ctx, ex);
            throw ex;
        }

        if (admission instanceof Admission.Completed completed) {
            return replayResult(ctx, idempotencyKey, completed.value());
        }
        if (admission instanceof Admission.InFlight inFlight) {
            try {
                return replayResult(ctx, idempotencyKey, IdempotencyService.awaitResult(inFlight.future()));
            } catch (GovernanceException ex) {
                // 合流到的首次提交失败（如首位撞上限流）：按首次提交的同一原因拒绝并留痕。
                auditReject(ctx, ex);
                throw ex;
            }
        }

        // 3) 只有首次提交（leader）才占用窗口配额：超限拒绝并放开占位，允许后续同键重试。
        Admission.Leader leader = (Admission.Leader) admission;
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
            idempotency.failed(leader, ex);
            auditReject(ctx, ex);
            throw ex;
        }

        // 4) 执行业务（副作用仅此一处、恰好一次），随后固化首次结果唤醒合流等待者。
        String orderId;
        try {
            orderId = orders.createOrder(resourceTenant, payload);
        } catch (RuntimeException | Error ex) {
            // 业务未成功：回退预占配额、放开占位，允许后续用相同键重试。
            quota.release(ctx.tenantId(), ACTION_CREATE_ORDER);
            idempotency.failed(leader, ex);
            throw ex;
        }
        idempotency.succeeded(leader, orderId);
        AuditEntry entry = audit.record(
                ctx.requestId(), ctx.tenantId(), ACTION_CREATE_ORDER, "ACCEPT",
                "quota ok (" + outcome.used() + "/" + outcome.limit() + "), order created");
        return new GovernanceResult<>(orderId, false, entry.seq());
    }

    /** 重放统一收尾：原样返回首次结果，不占配额、不产生副作用，仅追加 REPLAY 审计。 */
    private GovernanceResult<String> replayResult(RequestContext ctx, String idempotencyKey, Object value) {
        AuditEntry entry = audit.record(
                ctx.requestId(), ctx.tenantId(), ACTION_CREATE_ORDER, "REPLAY",
                "idempotent replay of key " + idempotencyKey + " (quota not consumed)");
        return new GovernanceResult<>((String) value, true, entry.seq());
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
