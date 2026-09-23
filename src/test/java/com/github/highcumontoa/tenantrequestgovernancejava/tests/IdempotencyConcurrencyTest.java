package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.business.OrderService;
import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RequestContext;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceResult;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 幂等语义测试：重复提交、同键不同内容冲突、并发重复提交副作用仅一次。
 */
class IdempotencyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConcurrencyTest.class);

    private AccessControlService access;
    private AuditService audit;
    private OrderService orders;
    private GovernanceFacade facade;

    @BeforeEach
    void setUp() {
        access = new AccessControlService();
        access.registerTenant("tenant-a", Set.of("READER", "WRITER", "ADMIN"));
        // 固定时钟，窗口不滚动；配额给足，专注验证幂等。
        QuotaService quota = new QuotaService(60_000L, () -> 1_000L);
        quota.updateQuota("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER, 10_000L);
        audit = new AuditService();
        orders = new OrderService();
        facade = new GovernanceFacade(access, quota, new IdempotencyService(), audit, orders);
    }

    private RequestContext ctx(String requestId) {
        return new RequestContext(requestId, "tenant-a", "WRITER", "tenant-a");
    }

    @Test
    void duplicateKeyReturnsFirstResultAndSideEffectOnce() {
        GovernanceResult<String> first = facade.createOrder(ctx("req-idem-1"), "key-1", "payload-A");
        GovernanceResult<String> second = facade.createOrder(ctx("req-idem-2"), "key-1", "payload-A");
        GovernanceResult<String> third = facade.createOrder(ctx("req-idem-3"), "key-1", "payload-A");

        log.info("[req-idem-1] first accept orderId={} auditSeq={} decision=ACCEPT", first.value(), first.auditSeq());
        log.info("[req-idem-2] duplicate orderId={} replayed={} auditSeq={} decision=REPLAY",
                second.value(), second.replayed(), second.auditSeq());
        log.info("[req-idem-3] duplicate orderId={} replayed={} auditSeq={} decision=REPLAY",
                third.value(), third.replayed(), third.auditSeq());

        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertTrue(third.replayed());
        assertEquals(first.value(), second.value());
        assertEquals(first.value(), third.value());
        assertEquals(1L, orders.sideEffectCount(), "业务副作用只能发生一次");
    }

    @Test
    void sameKeyDifferentBodyIsRejectedAsConflict() {
        facade.createOrder(ctx("req-conf-1"), "key-conflict", "body-v1");
        GovernanceException ex = assertThrows(GovernanceException.class,
                () -> facade.createOrder(ctx("req-conf-2"), "key-conflict", "body-v2-DIFFERENT"));
        AuditEntry last = audit.all().get(audit.all().size() - 1);
        log.info("[req-conf-2] rejected reason={} auditSeq={} decision=REJECT basis={}",
                ex.getReason(), last.seq(), last.reason());
        assertEquals(RejectReason.IDEMPOTENCY_CONFLICT, ex.getReason());
        assertEquals("REJECT", last.decision());
        assertEquals(1L, orders.sideEffectCount());
    }

    @Test
    void concurrentDuplicatesProduceSingleSideEffect() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<String> orderIds = new ConcurrentLinkedQueue<>();
        AtomicInteger replays = new AtomicInteger();
        AtomicInteger rejects = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    GovernanceResult<String> r = facade.createOrder(
                            ctx("req-concurrent-" + idx), "key-hot", "same-body");
                    orderIds.add(r.value());
                    if (r.replayed()) {
                        replays.incrementAndGet();
                    }
                } catch (GovernanceException e) {
                    rejects.incrementAndGet();
                    log.warn("[req-concurrent-{}] rejected {}", idx, e.getReason());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        long distinctOrders = orderIds.stream().distinct().count();
        log.info("[concurrent] threads={} accepted={} replays={} rejects={} distinctOrderIds={} sideEffects={}",
                threads, orderIds.size(), replays.get(), rejects.get(), distinctOrders, orders.sideEffectCount());
        audit.all().stream()
                .filter(e -> e.requestId().startsWith("req-concurrent-"))
                .forEach(e -> log.info("[{}] decision={} auditSeq={} basis={}",
                        e.requestId(), e.decision(), e.seq(), e.reason()));

        assertEquals(0, rejects.get(), "相同内容的并发重复提交不应被拒绝");
        assertEquals(threads, orderIds.size());
        assertEquals(1, distinctOrders, "所有并发请求必须返回同一首次结果");
        assertEquals(threads - 1, replays.get(), "恰好一次首次提交，其余全部重放");
        assertEquals(1L, orders.sideEffectCount(), "并发重复提交不得产生重复副作用");
    }
}
