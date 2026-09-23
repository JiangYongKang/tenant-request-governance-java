package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import com.github.highcumontoa.tenantrequestgovernancejava.governance.AccessPolicy;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.AuditLog;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.QuotaService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.Decision;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.GovernanceRequest;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.GovernanceResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额：边界精确、并发计数准确、租户隔离、运行期调额立即生效且不丢计数。
 */
class QuotaBoundaryTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaBoundaryTest.class);

    private GovernanceService newService(QuotaService quota) {
        return new GovernanceService(new AccessPolicy(), quota, new IdempotencyService(), new AuditLog());
    }

    private GovernanceRequest writeRequest(String tenant, String id) {
        return new GovernanceRequest(id, tenant, tenant, "WRITER", "order.create", "key-" + id, "p");
    }

    private GovernanceRequest readRequest(String tenant, String id) {
        return new GovernanceRequest(id, tenant, tenant, "READER", "order.read", null, null);
    }

    @Test
    void quotaBoundaryIsExact() {
        QuotaService quota = new QuotaService();
        GovernanceService service = newService(quota);
        quota.setQuota("tenant-a", "order.create", 3);

        Decision[] decisions = new Decision[5];
        for (int i = 0; i < 5; i++) {
            GovernanceResult r = service.handle(writeRequest("tenant-a", "qb-" + i));
            decisions[i] = r.decision();
            log.info("requestId={} decision={} basis={} auditSeq={} remaining={}",
                    r.requestId(), r.decision(), r.detail(), r.auditSeq(), r.quotaRemaining());
        }
        assertSame(Decision.ALLOWED, decisions[0]);
        assertSame(Decision.ALLOWED, decisions[1]);
        assertSame(Decision.ALLOWED, decisions[2]);
        assertSame(Decision.QUOTA_EXCEEDED, decisions[3], "第 4 次必须超限");
        assertSame(Decision.QUOTA_EXCEEDED, decisions[4]);
    }

    @Test
    void concurrentRequestsCountAccuratelyAtBoundary() throws Exception {
        QuotaService quota = new QuotaService();
        GovernanceService service = newService(quota);
        quota.setQuota("tenant-a", "order.create", 50);

        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String id = "qc-" + i;
                tasks.add(() -> {
                    GovernanceResult r = service.handle(writeRequest("tenant-a", id));
                    if (r.decision() == Decision.ALLOWED) {
                        allowed.incrementAndGet();
                    } else {
                        assertSame(Decision.QUOTA_EXCEEDED, r.decision());
                        rejected.incrementAndGet();
                    }
                    log.info("requestId={} decision={} auditSeq={} remaining={}",
                            r.requestId(), r.decision(), r.auditSeq(), r.quotaRemaining());
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        log.info("== 结论: allowed={}, rejected={} (配额 50, 并发 200)", allowed.get(), rejected.get());
        assertEquals(50, allowed.get(), "并发下接受数必须精确等于配额");
        assertEquals(150, rejected.get());
    }

    @Test
    void noisyTenantDoesNotAffectOthers() {
        QuotaService quota = new QuotaService();
        GovernanceService service = newService(quota);
        quota.setQuota("tenant-a", "order.create", 2);
        quota.setQuota("tenant-b", "order.create", 2);

        // tenant-a 把自己的配额打爆
        for (int i = 0; i < 5; i++) {
            GovernanceResult r = service.handle(writeRequest("tenant-a", "noise-" + i));
            log.info("requestId={} tenant=tenant-a decision={} auditSeq={}",
                    r.requestId(), r.decision(), r.auditSeq());
        }
        // tenant-b 完全不受影响
        for (int i = 0; i < 2; i++) {
            GovernanceResult r = service.handle(writeRequest("tenant-b", "quiet-" + i));
            log.info("requestId={} tenant=tenant-b decision={} auditSeq={} remaining={}",
                    r.requestId(), r.decision(), r.auditSeq(), r.quotaRemaining());
            assertSame(Decision.ALLOWED, r.decision(), "其它租户可用性不受影响");
        }
    }

    @Test
    void runtimeQuotaChangeTakesEffectImmediatelyAndKeepsCounts() {
        QuotaService quota = new QuotaService();
        GovernanceService service = newService(quota);
        quota.setQuota("tenant-a", "order.create", 2);

        service.handle(writeRequest("tenant-a", "before-1"));
        service.handle(writeRequest("tenant-a", "before-2"));
        assertEquals(2, quota.usedOf("tenant-a", "order.create"));

        // 运行期把配额从 2 提到 5：已有计数 2 保留，因此还能再放 3 个
        quota.setQuota("tenant-a", "order.create", 5);
        for (int i = 0; i < 3; i++) {
            GovernanceResult r = service.handle(writeRequest("tenant-a", "after-" + i));
            log.info("requestId={} decision={} basis={} remaining={}",
                    r.requestId(), r.decision(), r.detail(), r.quotaRemaining());
            assertSame(Decision.ALLOWED, r.decision());
        }
        GovernanceResult over = service.handle(writeRequest("tenant-a", "over"));
        log.info("requestId={} decision={} basis={}", over.requestId(), over.decision(), over.detail());
        assertSame(Decision.QUOTA_EXCEEDED, over.decision());

        // 收紧到 1：立即生效，窗口内已有计数 5 不再丢失，新请求直接超限
        quota.setQuota("tenant-a", "order.create", 1);
        GovernanceResult over2 = service.handle(writeRequest("tenant-a", "over2"));
        log.info("requestId={} decision={} basis={}", over2.requestId(), over2.decision(), over2.detail());
        assertSame(Decision.QUOTA_EXCEEDED, over2.decision());
        assertTrue(quota.usedOf("tenant-a", "order.create") >= 5, "已有计数不丢失");
    }
}
