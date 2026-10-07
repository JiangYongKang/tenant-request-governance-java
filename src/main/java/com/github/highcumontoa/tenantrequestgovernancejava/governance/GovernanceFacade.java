package com.github.highcumontoa.tenantrequestgovernancejava.governance;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.business.OrderService;
import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RequestContext;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService.Leader;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService.Participation;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService.Waiter;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 请求治理编排门面：访问控制 -> 幂等判定 -> 配额限流 -> 业务执行 -> 审计留痕。
 * <p>每个被接受或被拒绝的关键操作都产生一条审计记录，序号单调递增。
 *
 * <p><b>判定顺序（关键）</b>：幂等判定先于配额占用。
 * 同租户同键、内容一致的重复提交（无论在先提交处理中还是已生效，也无论窗口是否已满、
 * 状态是否来自快照恢复）直接合流到首次结果，<b>不占用、也不预占配额</b>；
 * 只有真正的首次提交（leader）才去占窗口配额。这样窗口打满后幂等重放依旧成立，
 * 连续重放与并发重放都不会多算/少算窗口计数。同键不同内容在同一环节直接报
 * {@link RejectReason#IDEMPOTENCY_CONFLICT}，同样不碰配额。
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
     * 写操作：受访问控制 + 幂等 + 配额保护，全程审计。
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

        // 2) 幂等判定先于配额：重放/冲突在这里定案，全程不碰窗口计数。
        //    在先 leader 失败撤位的极小窗口里，join 会拿不到结果，外层重新 begin 参选。
        while (true) {
            Participation participation;
            try {
                participation = idempotency.begin(ctx.tenantId(), idempotencyKey, requestHash);
            } catch (GovernanceException conflict) {
                // 同键不同内容：可区分的冲突拒绝，不算重放、不算正常放行、不占配额。
                auditReject(ctx, conflict);
                throw conflict;
            }

            if (participation instanceof Waiter waiter) {
                Optional<String> joined;
                try {
                    joined = idempotency.join(waiter, ctx.tenantId());
                } catch (GovernanceException ex) {
                    // 合流到的在先 leader 失败（如它自己被配额超限拒绝）：没有首次结果可取，
                    // 本次重放按同一原因拒绝，且自身不占配额。
                    auditReject(ctx, ex);
                    throw ex;
                }
                if (joined.isEmpty()) {
                    // 在先 leader 刚失败并撤位：重新参选（成为新 leader 或合流更新的占位）。
                    continue;
                }
                AuditEntry entry = audit.record(
                        ctx.requestId(), ctx.tenantId(), ACTION_CREATE_ORDER, "REPLAY",
                        "idempotent replay of key " + idempotencyKey + " (quota not consumed)");
                return new GovernanceResult<>(joined.get(), true, entry.seq());
            }

            Leader leader = (Leader) participation;

            // 3) 只有真正的首次提交才占配额：超限即撤位并解流合者，审计后拒绝。
            QuotaService.AcquireOutcome outcome = quota.tryAcquire(ctx.tenantId(), ACTION_CREATE_ORDER);
            if (!outcome.allowed()) {
                GovernanceException ex = quotaExceeded(ctx.tenantId(), outcome);
                idempotency.fail(leader, ex);
                auditReject(ctx, ex);
                throw ex;
            }

            // 4) 执行业务（副作用只在首次提交发生），随后发布首次结果供合流。
            String value;
            try {
                value = orders.createOrder(resourceTenant, payload);
            } catch (RuntimeException | Error ex) {
                // 业务未成功：撤位（同键允许重新首次提交）并回退预占配额。
                idempotency.fail(leader, ex);
                quota.release(ctx.tenantId(), ACTION_CREATE_ORDER);
                throw ex;
            }
            idempotency.succeed(leader, value);
            AuditEntry entry = audit.record(
                    ctx.requestId(), ctx.tenantId(), ACTION_CREATE_ORDER, "ACCEPT",
                    "quota ok (" + outcome.used() + "/" + outcome.limit() + "), order created");
            return new GovernanceResult<>(value, false, entry.seq());
        }
    }

    private static GovernanceException quotaExceeded(String tenantId, QuotaService.AcquireOutcome outcome) {
        if (outcome.limit() < 0) {
            return new GovernanceException(RejectReason.QUOTA_EXCEEDED,
                    "no quota configured for tenant " + tenantId);
        }
        return new GovernanceException(RejectReason.QUOTA_EXCEEDED,
                "quota exhausted for tenant " + tenantId
                        + " (used " + outcome.used() + "/" + outcome.limit() + " in window)");
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
