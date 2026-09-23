package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.business.OrderService;
import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RequestContext;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配额限流测试：边界准确性、并发计数准确、租户隔离、窗口滚动、热更新不丢计数。
 */
class QuotaBoundaryTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaBoundaryTest.class);

    private AccessControlService access;
    private AuditService audit;
    private OrderService orders;
    private final AtomicLong now = new AtomicLong(1_000L);
    private QuotaService quota;
    private GovernanceFacade facade;

    @BeforeEach
    void setUp() {
        access = new AccessControlService();
        access.registerTenant("tenant-a", Set.of("READER", "WRITER", "ADMIN"));
        access.registerTenant("tenant-b", Set.of("READER", "WRITER", "ADMIN"));
        quota = new QuotaService(60_000L, now::get);
        audit = new AuditService();
        orders = new OrderService();
        facade = new GovernanceFacade(access, quota, new IdempotencyService(), audit, orders);
        quota.updateQuota("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER, 3L);
        quota.updateQuota("tenant-b", GovernanceFacade.ACTION_CREATE_ORDER, 5L);
    }

    private RequestContext ctx(String tenant, String requestId) {
        return new RequestContext(requestId, tenant, "WRITER", tenant);
    }

    private String uniqueKey() {
        return UUID.randomUUID().toString();
    }

    @Test
    void boundaryExactlyLimitAcceptedThenRejected() {
        for (int i = 1; i <= 3; i++) {
            String rid = "req-bound-" + i;
            var r = facade.createOrder(ctx("tenant-a", rid), uniqueKey(), "p");
            log.info("[{}] accepted used {}/3 auditSeq={}", rid, i, r.auditSeq());
            assertFalse(r.replayed());
        }
        String rid = "req-bound-over";
        GovernanceException ex = assertThrows(GovernanceException.class,
                () -> facade.createOrder(ctx("tenant-a", rid), uniqueKey(), "p"));
        AuditEntry rejectEntry = latestAudit(rid);
        log.info("[{}] rejected reason={} basis={} auditSeq={}",
                rid, ex.getReason(), ex.getMessage(), rejectEntry.seq());
        assertEquals(RejectReason.QUOTA_EXCEEDED, ex.getReason());
        assertEquals("REJECT", rejectEntry.decision());
        // 被拒绝的请求不得占用计数：窗口用量仍是 3。
        assertEquals(3L, quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));
    }

    @Test
    void burstInOneTenantDoesNotAffectOtherTenant() {
        // tenant-a 打满 3 次并超限。
        for (int i = 0; i < 4; i++) {
            try {
                facade.createOrder(ctx("tenant-a", "req-a-burst-" + i), uniqueKey(), "p");
            } catch (GovernanceException expected) {
                assertEquals(RejectReason.QUOTA_EXCEEDED, expected.getReason());
            }
        }
        // tenant-b 仍可独立使用自己的 5 次配额。
        for (int i = 1; i <= 5; i++) {
            String rid = "req-b-ok-" + i;
            var r = facade.createOrder(ctx("tenant-b", rid), uniqueKey(), "p");
            log.info("[{}] tenant-b unaffected, accepted auditSeq={}", rid, r.auditSeq());
        }
        GovernanceException bOver = assertThrows(GovernanceException.class,
                () -> facade.createOrder(ctx("tenant-b", "req-b-over"), uniqueKey(), "p"));
        log.info("[req-b-over] rejected reason={}", bOver.getReason());
        assertEquals(RejectReason.QUOTA_EXCEEDED, bOver.getReason());
        assertEquals(5L, quota.currentCount("tenant-b", GovernanceFacade.ACTION_CREATE_ORDER));
    }

    @Test
    void concurrentRequestsCountAccurately() throws Exception {
        quota.updateQuota("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER, 20L);
        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<String> accepted = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> rejected = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    facade.createOrder(ctx("tenant-a", "req-cc-" + idx), uniqueKey(), "p");
                    accepted.add("x");
                } catch (GovernanceException e) {
                    assertEquals(RejectReason.QUOTA_EXCEEDED, e.getReason());
                    rejected.add("x");
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
        log.info("[concurrent-quota] threads={} accepted={} rejected={} windowCount={}",
                threads, accepted.size(), rejected.size(),
                quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));
        assertEquals(20, accepted.size(), "恰好放行配额上限个请求");
        assertEquals(80, rejected.size(), "其余全部被限流");
        assertEquals(20L, quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER),
                "窗口计数必须准确，拒绝不计数");
    }

    @Test
    void windowResetsAfterRolling() {
        for (int i = 0; i < 3; i++) {
            facade.createOrder(ctx("tenant-a", "req-w1-" + i), uniqueKey(), "p");
        }
        assertThrows(GovernanceException.class,
                () -> facade.createOrder(ctx("tenant-a", "req-w1-over"), uniqueKey(), "p"));
        now.addAndGet(60_000L); // 窗口滚动
        var r = facade.createOrder(ctx("tenant-a", "req-w2-1"), uniqueKey(), "p");
        log.info("[req-w2-1] accepted after window reset, auditSeq={} count={}",
                r.auditSeq(), quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));
        assertEquals(1L, quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));
    }

    @Test
    void quotaHotUpdateTakesEffectImmediatelyAndPreservesCount() {
        facade.createOrder(ctx("tenant-a", "req-hot-1"), uniqueKey(), "p");
        facade.createOrder(ctx("tenant-a", "req-hot-2"), uniqueKey(), "p");
        assertEquals(2L, quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));

        // 已有计数 2，旧配额 3，再调小为 1：立即生效 -> 下一次应被拒绝，且计数不被重置。
        quota.updateQuota("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER, 1L);
        assertEquals(1L, quota.currentLimit("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));
        GovernanceException over = assertThrows(GovernanceException.class,
                () -> facade.createOrder(ctx("tenant-a", "req-hot-over"), uniqueKey(), "p"));
        assertEquals(RejectReason.QUOTA_EXCEEDED, over.getReason());
        assertEquals(2L, quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER),
                "热更新不得清空已有计数");

        // 再调大到 10，已有计数保留，还可继续放行 8 个。
        quota.updateQuota("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER, 10L);
        var r = facade.createOrder(ctx("tenant-a", "req-hot-3"), uniqueKey(), "p");
        log.info("[req-hot-3] accepted after raising quota, auditSeq={} count={}",
                r.auditSeq(), quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));
        assertEquals(3L, quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));
    }

    private AuditEntry latestAudit(String requestId) {
        return audit.all().stream()
                .filter(e -> requestId.equals(e.requestId()))
                .reduce((a, b) -> b)
                .orElseThrow();
    }
}
