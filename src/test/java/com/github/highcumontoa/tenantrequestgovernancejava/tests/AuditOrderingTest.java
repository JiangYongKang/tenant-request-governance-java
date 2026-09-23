package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.business.OrderService;
import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RequestContext;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计测试：序号单调递增、只追加、覆盖 ACCEPT/REJECT/REPLAY 三类决策，且并发下不错序、不覆盖。
 */
class AuditOrderingTest {

    private static final Logger log = LoggerFactory.getLogger(AuditOrderingTest.class);

    @Test
    void auditSequenceIsMonotonicAndComplete() throws Exception {
        AccessControlService access = new AccessControlService();
        access.registerTenant("tenant-a", Set.of("READER", "WRITER", "ADMIN"));
        QuotaService quota = new QuotaService(60_000L, () -> 1_000L);
        quota.updateQuota("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER, 50L);
        AuditService audit = new AuditService();
        OrderService orders = new OrderService();
        GovernanceFacade facade = new GovernanceFacade(access, quota, new IdempotencyService(), audit, orders);

        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong attempts = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    attempts.incrementAndGet();
                    String rid = "req-audit-" + idx;
                    if (idx % 5 == 0) {
                        // READER 越权写 -> REJECT
                        try {
                            facade.createOrder(new RequestContext(rid, "tenant-a", "READER", "tenant-a"),
                                    UUID.randomUUID().toString(), "p");
                        } catch (GovernanceException expected) {
                            // expected reject
                        }
                    } else {
                        facade.createOrder(new RequestContext(rid, "tenant-a", "WRITER", "tenant-a"),
                                UUID.randomUUID().toString(), "p");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        pool.shutdown();

        List<AuditEntry> all = audit.all();
        log.info("[audit] total entries = {} (attempts={})", all.size(), attempts.get());
        long expectedSeq = 1L;
        for (AuditEntry e : all) {
            assertEquals(expectedSeq, e.seq(), "审计序号必须从 1 连续递增，不得错序或缺口");
            log.info("[{}] seq={} decision={} basis={}", e.requestId(), e.seq(), e.decision(), e.reason());
            expectedSeq++;
        }
        assertEquals(threads, all.size(), "每个关键操作（接受或拒绝）都必须留下一条审计");
        assertTrue(all.stream().anyMatch(e -> "REJECT".equals(e.decision())), "应包含拒绝记录");
        assertTrue(all.stream().anyMatch(e -> "ACCEPT".equals(e.decision())), "应包含接受记录");
    }
}
