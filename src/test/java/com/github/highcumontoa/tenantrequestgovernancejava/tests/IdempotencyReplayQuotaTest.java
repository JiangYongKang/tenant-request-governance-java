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
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceStateLock;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import com.github.highcumontoa.tenantrequestgovernancejava.snapshot.SnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 幂等重放与限流配额交叉边界：
 * <ul>
 *   <li>窗口打满后，已生效幂等键的重放仍原样返回首次结果，不被 429 挡住、不占计数；</li>
 *   <li>快照接回（重启 / 运行期恢复）后，窗口打满状态下重放行为保持一致；</li>
 *   <li>重放与正在进行的首次提交并发时稳定合流到首次结果，计数不多不少；</li>
 *   <li>窗口打满后同键不同内容仍是可区分的 409 冲突，既不是重放也不算放行；</li>
 *   <li>真正的新键首次提交超出上限照常 429。</li>
 * </ul>
 * 全程固定时钟，结果可重复复现。
 */
class IdempotencyReplayQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyReplayQuotaTest.class);
    private static final long WINDOW = 60_000L;
    private static final String ACTION = GovernanceFacade.ACTION_CREATE_ORDER;

    /** createOrder 可被阻塞的订单服务，用于稳定制造“首次提交进行中”的并发时刻。 */
    private static final class BlockableOrderService extends OrderService {
        private final CountDownLatch enteredBusiness;
        private final CountDownLatch allowBusiness;

        BlockableOrderService(CountDownLatch enteredBusiness, CountDownLatch allowBusiness) {
            this.enteredBusiness = enteredBusiness;
            this.allowBusiness = allowBusiness;
        }

        @Override
        public String createOrder(String tenantId, String payload) {
            enteredBusiness.countDown();
            try {
                allowBusiness.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.createOrder(tenantId, payload);
        }
    }

    private static final class Harness {
        final AtomicLong clock;
        final AccessControlService access = new AccessControlService();
        final QuotaService quota;
        final IdempotencyService idempotency = new IdempotencyService();
        final AuditService audit = new AuditService();
        final OrderService orders;
        final GovernanceStateLock stateLock = new GovernanceStateLock();
        final GovernanceFacade facade;
        final SnapshotService snapshots;

        Harness(AtomicLong clock, long limit) {
            this(clock, limit, new OrderService());
        }

        Harness(AtomicLong clock, long limit, OrderService orders) {
            this.clock = clock;
            this.quota = new QuotaService(WINDOW, clock::get);
            this.orders = orders;
            access.registerTenant("tenant-a", Set.of("READER", "WRITER", "ADMIN"));
            quota.updateQuota("tenant-a", ACTION, limit);
            facade = new GovernanceFacade(access, quota, idempotency, audit, orders, stateLock);
            snapshots = new SnapshotService(quota, idempotency, audit, stateLock);
        }

        GovernanceResult<String> submit(String requestId, String key, String payload) {
            GovernanceResult<String> r = facade.createOrder(
                    new RequestContext(requestId, "tenant-a", "WRITER", "tenant-a"), key, payload);
            AuditEntry e = audit.all().stream().filter(a -> requestId.equals(a.requestId()))
                    .reduce((a, b) -> b).orElseThrow();
            log.info("[{}] decision={} auditSeq={} basis={} orderId={} windowUsed={}",
                    requestId, e.decision(), e.seq(), e.reason(), r.value(),
                    quota.currentCount("tenant-a", ACTION));
            return r;
        }

        GovernanceException submitExpectReject(String requestId, String key, String payload) {
            try {
                facade.createOrder(new RequestContext(requestId, "tenant-a", "WRITER", "tenant-a"),
                        key, payload);
                fail("expected rejection for " + requestId);
                return null;
            } catch (GovernanceException ex) {
                AuditEntry e = audit.all().stream().filter(a -> requestId.equals(a.requestId()))
                        .reduce((a, b) -> b).orElseThrow();
                log.info("[{}] decision=REJECT reason={} auditSeq={} basis={} windowUsed={}",
                        requestId, ex.getReason(), e.seq(), ex.getMessage(),
                        quota.currentCount("tenant-a", ACTION));
                return ex;
            }
        }
    }

    /** 窗口打满：已生效的键重放 N 次全部返回首次结果，计数既不多算也不少算；新键仍 429。 */
    @Test
    void replayAfterWindowExhaustedReturnsFirstResultAndDoesNotConsumeQuota() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        Harness h = new Harness(clock, 3L);

        GovernanceResult<String> first = h.submit("req-full-1", "key-original", "body-A");
        h.submit("req-full-2", "key-other-2", "body-2");
        h.submit("req-full-3", "key-other-3", "body-3");
        assertEquals(3L, h.quota.currentCount("tenant-a", ACTION));
        assertEquals(3L, h.orders.sideEffectCount());

        // 窗口已打满：真正的新键首次提交必须照常 429，且计数不被多占。
        GovernanceException over = h.submitExpectReject("req-full-over", "key-brand-new", "body-new");
        assertEquals(RejectReason.QUOTA_EXCEEDED, over.getReason());
        assertEquals(3L, h.quota.currentCount("tenant-a", ACTION), "被拒请求不得占用计数");

        // 已生效键连续重放多次：不能被 429 挡住，全部原样返回首次结果。
        for (int i = 0; i < 5; i++) {
            String rid = "req-replay-" + i;
            GovernanceResult<String> replay = h.submit(rid, "key-original", "body-A");
            assertTrue(replay.replayed(), rid + " 必须判定为重放");
            assertEquals(first.value(), replay.value(), rid + " 必须原样返回首次订单结果");
        }
        assertEquals(3L, h.quota.currentCount("tenant-a", ACTION), "重放不得占用/回退窗口计数");
        assertEquals(3L, h.orders.sideEffectCount(), "重放不得再次产生业务副作用");

        // 重放之后，新键首次提交仍应按配额超限拒绝。
        GovernanceException overAgain = h.submitExpectReject("req-full-over-2", "key-brand-new-2", "body-new-2");
        assertEquals(RejectReason.QUOTA_EXCEEDED, overAgain.getReason());
        assertEquals(3L, h.quota.currentCount("tenant-a", ACTION));
    }

    /** 窗口打满状态导出快照 -> 新实例接回 -> 重放仍返回首次结果；新键仍 429；计数接续。 */
    @Test
    void replayAfterSnapshotRestoreWhenWindowExhausted(@TempDir Path dir) {
        AtomicLong clock = new AtomicLong(1_000_000L);
        Harness h1 = new Harness(clock, 2L);
        GovernanceResult<String> first = h1.submit("req-snap-1", "key-snap", "body-snap");
        h1.submit("req-snap-2", "key-snap-2", "body-snap-2");
        assertEquals(2L, h1.quota.currentCount("tenant-a", ACTION));
        Path file = dir.resolve("exhausted-snapshot.json");
        h1.snapshots.exportTo(file);

        // 模拟重启：全新组件接回快照（配额配置也来自快照，先给 0）。
        Harness h2 = new Harness(clock, 0L);
        h2.snapshots.restoreFrom(file);
        assertEquals(2L, h2.quota.currentCount("tenant-a", ACTION), "窗口已用计数必须接续");
        assertEquals(2L, h2.quota.currentLimit("tenant-a", ACTION));

        // 打满状态下接回后重放：原样返回首次结果，不占计数、不产生副作用。
        for (int i = 0; i < 3; i++) {
            GovernanceResult<String> replay = h2.submit("req-snap-replay-" + i, "key-snap", "body-snap");
            assertTrue(replay.replayed());
            assertEquals(first.value(), replay.value());
        }
        assertEquals(2L, h2.quota.currentCount("tenant-a", ACTION), "接回后的重放不得改动窗口计数");
        assertEquals(0L, h2.orders.sideEffectCount(), "接回后的重放不得在新进程产生副作用");

        // 接回后真正的新键首次提交：窗口已满，照常 429。
        GovernanceException over = h2.submitExpectReject("req-snap-over", "key-snap-new", "body-new");
        assertEquals(RejectReason.QUOTA_EXCEEDED, over.getReason());
        assertEquals(2L, h2.quota.currentCount("tenant-a", ACTION));
    }

    /** 重放与正在进行的首次提交并发：所有参与方拿到同一个首次结果，恰好一次副作用、一次配额计数。 */
    @Test
    void concurrentReplayJoinsInFlightFirstSubmission() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        int replayThreads = 15;
        CountDownLatch enteredBusiness = new CountDownLatch(1);
        CountDownLatch allowBusiness = new CountDownLatch(1);
        Harness h = new Harness(clock, 10_000L,
                new BlockableOrderService(enteredBusiness, allowBusiness));

        ExecutorService pool = Executors.newFixedThreadPool(replayThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(replayThreads + 1);
        ConcurrentLinkedQueue<String> orderIds = new ConcurrentLinkedQueue<>();
        AtomicReference<String> firstOrderId = new AtomicReference<>();
        AtomicLong replays = new AtomicLong();
        AtomicLong rejects = new AtomicLong();

        // Leader：admit 占位 + 占配额后在业务内部阻塞，稳定制造“首次提交进行中”窗口。
        pool.submit(() -> {
            try {
                GovernanceResult<String> leader = h.facade.createOrder(
                        new RequestContext("req-inflight-leader", "tenant-a", "WRITER", "tenant-a"),
                        "key-inflight", "same-body");
                firstOrderId.set(leader.value());
                orderIds.add(leader.value());
            } finally {
                done.countDown();
            }
        });
        assertTrue(enteredBusiness.await(5, TimeUnit.SECONDS), "leader 必须已经进入业务（占位已生效）");

        for (int i = 0; i < replayThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    GovernanceResult<String> r = h.facade.createOrder(
                            new RequestContext("req-inflight-r-" + idx, "tenant-a", "WRITER", "tenant-a"),
                            "key-inflight", "same-body");
                    orderIds.add(r.value());
                    if (r.replayed()) {
                        replays.incrementAndGet();
                    }
                } catch (GovernanceException ex) {
                    rejects.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        // 让重放线程进入 admit 看到 InFlight 并 join，然后再放行 leader 完成业务。
        Thread.sleep(100);
        allowBusiness.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[concurrent-inflight] replays={} rejects={} distinctOrderIds={} sideEffects={} windowUsed={}",
                replays.get(), rejects.get(), orderIds.stream().distinct().count(),
                h.orders.sideEffectCount(), h.quota.currentCount("tenant-a", ACTION));
        h.audit.all().stream()
                .filter(e -> e.requestId().startsWith("req-inflight"))
                .forEach(e -> log.info("[{}] decision={} auditSeq={} basis={}",
                        e.requestId(), e.decision(), e.seq(), e.reason()));

        assertEquals(0L, rejects.get(), "并发重放不得被拒绝");
        assertEquals(replayThreads, replays.get(), "后到的请求必须全部判定为重放");
        assertEquals(1L, orderIds.stream().distinct().count(), "必须全部合流到同一个首次订单");
        assertEquals(firstOrderId.get(), orderIds.peek());
        assertEquals(1L, h.orders.sideEffectCount(), "副作用只能发生一次");
        assertEquals(1L, h.quota.currentCount("tenant-a", ACTION), "窗口只能计首次提交一次");
    }

    /**
     * 窗口打满后同键不同内容：仍必须是可区分的 409 冲突，
     * 不能当重放返回旧结果，也不能算作放行（计数不变）；快照接回后同样保持。
     */
    @Test
    void sameKeyDifferentBodyStillConflictsAfterWindowExhaustedAndAfterRestore(@TempDir Path dir) {
        AtomicLong clock = new AtomicLong(1_000_000L);
        Harness h = new Harness(clock, 2L);
        GovernanceResult<String> first = h.submit("req-cf-1", "key-cf", "body-v1");
        h.submit("req-cf-2", "key-cf-other", "body-x");
        assertEquals(2L, h.quota.currentCount("tenant-a", ACTION));

        // 窗口已满：同键不同内容必须 409，而不是 429，更不能返回旧结果。
        GovernanceException conflict = h.submitExpectReject("req-cf-diff", "key-cf", "body-v2-DIFFERENT");
        assertEquals(RejectReason.IDEMPOTENCY_CONFLICT, conflict.getReason());
        assertEquals(2L, h.quota.currentCount("tenant-a", ACTION), "冲突拒绝不得改动计数");
        assertEquals(2L, h.orders.sideEffectCount());

        // 同键相同内容仍可正常重放，且仍不占配额。
        GovernanceResult<String> replay = h.submit("req-cf-replay", "key-cf", "body-v1");
        assertTrue(replay.replayed());
        assertEquals(first.value(), replay.value());
        assertEquals(2L, h.quota.currentCount("tenant-a", ACTION));

        // 接回快照后边界保持：冲突依旧 409，重放依旧成功。
        Path file = dir.resolve("conflict-snapshot.json");
        h.snapshots.exportTo(file);
        Harness h2 = new Harness(clock, 0L);
        h2.snapshots.restoreFrom(file);
        GovernanceException conflictAfter = h2.submitExpectReject("req-cf-diff-restored",
                "key-cf", "body-v2-DIFFERENT");
        assertEquals(RejectReason.IDEMPOTENCY_CONFLICT, conflictAfter.getReason());
        assertEquals(2L, h2.quota.currentCount("tenant-a", ACTION));
        GovernanceResult<String> replayAfter = h2.submit("req-cf-replay-restored", "key-cf", "body-v1");
        assertTrue(replayAfter.replayed());
        assertEquals(first.value(), replayAfter.value());
        assertEquals(2L, h2.quota.currentCount("tenant-a", ACTION), "接回后重放/冲突都不得改动计数");
        assertEquals(0L, h2.orders.sideEffectCount());
    }

    /** 首次提交失败（撞限流）后放开占位，后续同键同内容在有配额时可作为全新首次提交成功。 */
    @Test
    void rejectedFirstSubmissionReleasesSlotAndCanRetry() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        Harness h = new Harness(clock, 1L);
        h.submit("req-r-1", "key-busy", "b");
        assertEquals(1L, h.quota.currentCount("tenant-a", ACTION));

        // 窗口满：新键首次提交 429，占位必须放开。
        GovernanceException over = h.submitExpectReject("req-r-over", "key-retry", "body-retry");
        assertEquals(RejectReason.QUOTA_EXCEEDED, over.getReason());

        // 窗口滚动后同一键同一内容重试：应作为首次提交成功（不是重放），副作用正常发生。
        h.clock.addAndGet(WINDOW + 1);
        GovernanceResult<String> retry = h.submit("req-r-retry-ok", "key-retry", "body-retry");
        assertFalse(retry.replayed(), "放开占位后的重试必须是全新首次提交");
        assertEquals(1L, h.quota.currentCount("tenant-a", ACTION));
        assertEquals(2L, h.orders.sideEffectCount());
    }
}
