package com.github.highcumontoa.tenantrequestgovernancejava.governance;

import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.Decision;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.GovernanceRequest;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.GovernanceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 治理编排，固定顺序为：访问控制 → 配额限流 → 幂等执行 → 审计留痕。
 * <p>每个被接受或被拒绝的关键操作都会产生一条审计记录。
 */
@Service
public class GovernanceService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceService.class);

    private final AccessPolicy accessPolicy;
    private final QuotaService quotaService;
    private final IdempotencyService idempotencyService;
    private final AuditLog auditLog;

    /** 模拟业务副作用发生次数，用于验证幂等保证下副作用最多一次。 */
    private final AtomicLong sideEffectCount = new AtomicLong();

    public GovernanceService(AccessPolicy accessPolicy, QuotaService quotaService,
                             IdempotencyService idempotencyService, AuditLog auditLog) {
        this.accessPolicy = accessPolicy;
        this.quotaService = quotaService;
        this.idempotencyService = idempotencyService;
        this.auditLog = auditLog;
    }

    public GovernanceResult handle(GovernanceRequest request) {
        // 1. 访问控制：未知租户 / 跨租户 / 角色越权，原因可区分
        Decision denied = accessPolicy.check(request.tenantId(), request.targetTenantId(),
                request.role(), request.operation());
        if (denied != null) {
            return reject(request, denied, "access denied: " + denied);
        }

        // 2. 配额限流：每个到达请求（含幂等重放）都占用窗口内一次计数
        long remaining = quotaService.tryAcquire(request.tenantId(), request.operation());
        if (remaining < 0) {
            return reject(request, Decision.QUOTA_EXCEEDED,
                    "quota exhausted for tenant=" + request.tenantId()
                            + ", op=" + request.operation() + ", limit="
                            + quotaService.quotaOf(request.tenantId(), request.operation())
                            + ", used=" + quotaService.usedOf(request.tenantId(), request.operation()));
        }

        // 3. 幂等执行：无幂等键的请求（如读操作）直接执行
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            String body = executeEffect(request);
            return accept(request, Decision.ALLOWED, "request accepted and executed", body, remaining);
        }

        IdempotencyService.Outcome outcome = idempotencyService.submit(
                request.tenantId(), request.idempotencyKey(), request.payload(),
                () -> executeEffect(request));
        return switch (outcome.status()) {
            case EXECUTED -> accept(request, Decision.ALLOWED,
                    "first submission, effect executed once", outcome.body(), remaining);
            case REPLAYED -> accept(request, Decision.IDEMPOTENT_REPLAY,
                    "duplicate idempotency key, returning first result, no new side effect",
                    outcome.body(), remaining);
            case CONFLICT -> reject(request, Decision.IDEMPOTENCY_CONFLICT,
                    "same idempotency key submitted with different payload");
        };
    }

    /** 模拟业务副作用：唯一结果体，可观测的副作用计数。 */
    private String executeEffect(GovernanceRequest request) {
        long n = sideEffectCount.incrementAndGet();
        String body = "effect:" + request.operation() + "#" + n;
        log.info("[effect] requestId={} tenant={} op={} sideEffectSeq={}",
                request.requestId(), request.tenantId(), request.operation(), n);
        return body;
    }

    public long sideEffectCount() {
        return sideEffectCount.get();
    }

    private GovernanceResult reject(GovernanceRequest request, Decision decision, String detail) {
        AuditEntry entry = auditLog.append(request.requestId(), request.tenantId(), request.role(),
                request.operation(), request.idempotencyKey(), decision, detail);
        log.info("[decision] requestId={} decision={} auditSeq={} basis={}",
                request.requestId(), decision, entry.seq(), detail);
        return new GovernanceResult(request.requestId(), decision, detail, entry.seq(), null, -1L);
    }

    private GovernanceResult accept(GovernanceRequest request, Decision decision, String detail,
                                    String body, long remaining) {
        AuditEntry entry = auditLog.append(request.requestId(), request.tenantId(), request.role(),
                request.operation(), request.idempotencyKey(), decision, detail);
        log.info("[decision] requestId={} decision={} auditSeq={} basis={} quotaRemaining={}",
                request.requestId(), decision, entry.seq(), detail, remaining);
        return new GovernanceResult(request.requestId(), decision, detail, entry.seq(), body, remaining);
    }
}
