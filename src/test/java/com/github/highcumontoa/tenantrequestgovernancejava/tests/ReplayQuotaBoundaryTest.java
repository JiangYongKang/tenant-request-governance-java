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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 窗口打满边界下的幂等重放测试：
 * 1) 窗口打满后同键同内容重放仍返回首次结果、不占配额、无重复副作用；
 * 2) 快照接回（含窗口已满的计数）后重放表现一致；
 * 3) 重放与“正在进行的首次提交”并发时稳定合流，窗口计数不多不少；
 * 4) 同键不同内容在窗口满 / 恢复后都必须报可区分的 IDEMPOTENCY_CONFLICT，
 *    既不被当成重放，也不算一次正常放行。
 * 全部使用固定时钟 + 发令闩锁，反复执行结果一致。
 */
class ReplayQuotaBoundaryTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayQuotaBoundaryTest.class);
    private static final String ACTION = GovernanceFacade.ACTION_CREATE_ORDER;
    private static final String TENANT = "tenant-a";

    /** 接线好的一套治理组件；固定时钟让窗口永不滚动，结论不依赖真实时间。 */
    private static final class Harness {
        final AccessControlService access = new AccessControlService();
        final QuotaService quota = new QuotaService(60_000L, () -> 1_000L);
        final IdempotencyService idempotency = new IdempotencyService();
        final AuditService audit = new AuditService();
        final OrderService orders;
        final GovernanceStateLock stateLock = new GovernanceStateLock();
        final GovernanceFacade facade;
        final SnapshotService snapshots;

        Harness(long quotaLimit) {
            this(quotaLimit, new OrderService());
        }

        Harness(long quotaLimit, OrderService orderService) {
            access.registerTenant(TENANT, Set.of("READER", "WRITER", "ADMIN"));
            quota.updateQuota(TENANT, ACTION, quotaLimit);
            this.orders = orderService;
            this.facade = new GovernanceFacade(access, quota, idempotency, audit, orders, stateLock);
            this.snapshots = new SnapshotService(quota, idempotency, audit, stateLock);
        }

        GovernanceResult<String> submit(String requestId, String key, String payload) {
            GovernanceResult<String> r = facade.createOrder(
                    new RequestContext(requestId, TENANT, "WRITER", TENANT), key, payload);
            AuditEntry e = audit.all().stream()
                    .filter(a -> requestId.equals(a.requestId())).reduce((a, b) -> b).orElseThrow();
            log.info("[{}] decision={} replayed={} auditSeq={} basis={} windowUsed={}",
                    requestId, e.decision(), r.replayed(), e.seq(), e.reason(),
                    quota.currentCount(TENANT, ACTION));
            return r;
        }

        GovernanceException submitExpectReject(String requestId, String key, String payload) {
            try {
                facade.createOrder(new RequestContext(requestId, TENANT, "WRITER", TENANT), key, payload);
                fail("expected reject for " + requestId);
                return null;
            } catch (GovernanceException ex) {
                AuditEntry e = audit.all().stream()
                        .filter(a -> requestId.equals(a.requestId())).reduce((a, b) -> b).orElseThrow();
                log.info("[{}] rejected reason={} auditSeq={} basis={} windowUsed={}",
                        requestId, ex.getReason(), e.seq(), e.reason(),
                        quota.currentCount(TENANT, ACTION));
                return ex;
            }
        }

        long used() {
            return quota.currentCount(TENANT, ACTION);
        }
    }

    /** 可在业务执行中途挂起的订单服务：进入后通知 entered，放开 allow 前首次提交一直处理中。 */
    private static final class BlockingOrderService extends OrderService {
        private final CountDownLatch entered;
        private final CountDownLatch allow;

        BlockingOrderService(CountDownLatch entered, CountDownLatch allow) {
            this.entered = entered;
            this.allow = allow;
        }

        @Override
        public String createOrder(String tenantId, String payload) {
            entered.countDown();
            try {
                assertTrue(allow.await(10, TimeUnit.SECONDS), "测试闸门异常：首次提交未能在时限内被放行");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return super.createOrder(tenantId, payload);
        }
    }

    @Test
    void replayAfterWindowExhaustedReturnsFirstResultWithoutConsumingQuota() {
        Harness h = new Harness(2L);

        // 两个不同幂等键的首次提交，恰好把窗口打满 2/2。
        GovernanceResult<String> first = h.submit("req-full-1", "key-1", "payload-1");
        h.submit("req-full-2", "key-2", "payload-2");
        assertFalse(first.replayed());
        assertEquals(2L, h.used());

        // 窗口已满：同键同内容连续重放，必须原样返回首次结果，且计数纹丝不动。
        for (int i = 1; i <= 3; i++) {
            String rid = "req-replay-" + i;
            GovernanceResult<String> replay = h.submit(rid, "key-1", "payload-1");
            assertTrue(replay.replayed(), rid + " 必须是重放");
            assertEquals(first.value(), replay.value(), rid + " 必须返回第一次的订单结果");
            assertEquals(2L, h.used(), rid + " 重放不得多占配额");
        }
        assertEquals(2L, h.orders.sideEffectCount(), "重放不得产生第二次业务副作用");

        // 真正的首次提交（新键）在窗口满时仍照常按配额超限拒绝，且计数不被多算/少算。
        GovernanceException over = h.submitExpectReject("req-new-over", "key-NEW", "payload-NEW");
        assertEquals(RejectReason.QUOTA_EXCEEDED, over.getReason());
        assertEquals(2L, h.used(), "被限流请求不得改变窗口计数");

        // 被拒之后旧键重放依旧成立。
        GovernanceResult<String> replayAfter = h.submit("req-replay-after", "key-1", "payload-1");
        assertTrue(replayAfter.replayed());
        assertEquals(first.value(), replayAfter.value());
        assertEquals(2L, h.used());
        assertEquals(2L, h.orders.sideEffectCount());
    }

    @Test
    void replayAndConflictAfterSnapshotRestoreWhenWindowExhausted(@TempDir Path dir) {
        Harness h1 = new Harness(1L);
        GovernanceResult<String> first = h1.submit("req-pre-1", "key-restored", "payload-v1");
        assertEquals(1L, h1.used());
        // 打满后再确认：恢复前重放本就不该被限流挡住。
        GovernanceResult<String> before = h1.submit("req-pre-replay", "key-restored", "payload-v1");
        assertTrue(before.replayed());
        assertEquals(1L, h1.used());
        Path snap = dir.resolve("exhausted-window.json");
        h1.snapshots.exportTo(snap);

        // 模拟重启：全新组件实例，从快照接回“窗口已打满 + 已有幂等结果”的状态。
        Harness h2 = new Harness(0L);
        h2.snapshots.restoreFrom(snap);
        assertEquals(1L, h2.used(), "快照中的已用计数必须接回");
        assertEquals(1L, h2.quota.currentLimit(TENANT, ACTION));

        // 1) 接回后窗口已满，同键同内容重放仍返回首次结果，新实例无副作用、计数不变。
        for (int i = 1; i <= 3; i++) {
            String rid = "req-post-replay-" + i;
            GovernanceResult<String> replay = h2.submit(rid, "key-restored", "payload-v1");
            assertTrue(replay.replayed(), rid + " 恢复后重放必须成立");
            assertEquals(first.value(), replay.value(), rid + " 必须原样返回首次结果");
            assertEquals(0L, h2.orders.sideEffectCount(), rid + " 恢复后的重放不得触发副作用");
            assertEquals(1L, h2.used(), rid + " 恢复后的重放不得占用配额");
        }

        // 2) 接回后同键不同内容：必须是可区分的冲突，而不是重放，也不是限流。
        GovernanceException conflict =
                h2.submitExpectReject("req-post-conflict", "key-restored", "payload-DIFFERENT");
        assertEquals(RejectReason.IDEMPOTENCY_CONFLICT, conflict.getReason());
        assertEquals(1L, h2.used(), "冲突请求不得算作一次放行");
        assertEquals(0L, h2.orders.sideEffectCount());

        // 3) 冲突没有污染幂等记录：相同内容仍可正常重放。
        GovernanceResult<String> replayAgain = h2.submit("req-post-replay-again", "key-restored", "payload-v1");
        assertTrue(replayAgain.replayed());
        assertEquals(first.value(), replayAgain.value());

        // 4) 新键的真正首次提交在接回的满窗口上仍被配额拒绝。
        GovernanceException over = h2.submitExpectReject("req-post-over", "key-OTHER", "payload-other");
        assertEquals(RejectReason.QUOTA_EXCEEDED, over.getReason());
        assertEquals(1L, h2.used());
    }

    @Test
    void replayConcurrentWithInFlightFirstSubmissionJoinsAndDoesNotConsumeQuota() throws Exception {
        // 重复多轮，保证该并发结论稳定可复现。
        for (int round = 0; round < 5; round++) {
            runOneConcurrentRound(round);
        }
    }

    private void runOneConcurrentRound(int round) throws Exception {
        int replays = 8;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allow = new CountDownLatch(1);
        Harness h = new Harness(1L, new BlockingOrderService(entered, allow));
        String key = "key-inflight-" + round;
        ExecutorService pool = Executors.newFixedThreadPool(replays + 1);

        // 首次提交先跑起来并在业务中途挂住：此时它已占用唯一配额（窗口 1/1），但结果尚未生效。
        CountDownLatch leaderDone = new CountDownLatch(1);
        ConcurrentLinkedQueue<GovernanceResult<String>> leaderResult = new ConcurrentLinkedQueue<>();
        pool.submit(() -> {
            try {
                leaderResult.add(h.submit("req-leader-" + round, key, "same-body"));
            } finally {
                leaderDone.countDown();
            }
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS), "首轮：首次提交未进入业务");
        assertEquals(1L, h.used(), "首次提交应已占住唯一配额");

        // 窗口已满 + 首次提交仍在进行：并发重放必须合流等待，不能被限流，也不能提前返回。
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(replays);
        ConcurrentLinkedQueue<GovernanceResult<String>> replayResults = new ConcurrentLinkedQueue<>();
        AtomicInteger replayRejects = new AtomicInteger();
        for (int i = 0; i < replays; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    replayResults.add(h.submit("req-join-" + round + "-" + idx, key, "same-body"));
                } catch (GovernanceException ex) {
                    replayRejects.incrementAndGet();
                    log.warn("[req-join-{}-{}] unexpectedly rejected: {}", round, idx, ex.getReason());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();

        // 静默期：正确实现下重放都在等待首次结果，既不会有结果返回也不会有拒绝；计数保持 1。
        long quietDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        while (System.nanoTime() < quietDeadline) {
            assertEquals(0, replayResults.size(), "首次提交完成前重放不得提前返回");
            assertEquals(0, replayRejects.get(), "进行中的同键重放不得被限流/冲突拒绝");
            assertEquals(1L, h.used(), "等待中的重放不得改动窗口计数");
            Thread.sleep(10);
        }

        // 放开首次提交，重放合流到同一结果。
        allow.countDown();
        assertTrue(leaderDone.await(10, TimeUnit.SECONDS));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        GovernanceResult<String> first = leaderResult.element();
        assertFalse(first.replayed());
        assertEquals(0, replayRejects.get(), "并发重放全部必须合流成功");
        assertEquals(replays, replayResults.size());
        for (GovernanceResult<String> r : replayResults) {
            assertTrue(r.replayed());
            assertEquals(first.value(), r.value(), "重放必须返回首次订单结果");
        }
        assertEquals(1L, h.orders.sideEffectCount(), "并发合流下副作用只能一次");
        assertEquals(1L, h.used(), "8 个并发重放不得多占一个配额");

        // 首次提交完成后再连续重放多次，结论不变；新键仍被限流。
        for (int i = 0; i < 5; i++) {
            GovernanceResult<String> again = h.submit("req-after-" + round + "-" + i, key, "same-body");
            assertTrue(again.replayed());
            assertEquals(first.value(), again.value());
        }
        assertEquals(1L, h.orders.sideEffectCount());
        assertEquals(1L, h.used());
        GovernanceException over = h.submitExpectReject("req-after-over-" + round, "key-new-" + round, "p");
        assertEquals(RejectReason.QUOTA_EXCEEDED, over.getReason());
        assertEquals(1L, h.used());
        log.info("[concurrent-round-{}] leader=1 replays={} joined sideEffects={} windowUsed=1/1",
                round, replays);
    }

    @Test
    void sameKeyDifferentBodyConflictsEvenWhenWindowExhausted() {
        Harness h = new Harness(1L);
        GovernanceResult<String> first = h.submit("req-cf-1", "key-cf", "body-v1");
        assertEquals(1L, h.used());

        // 窗口已满：同键不同内容必须报冲突（可区分于限流），不返回旧结果，也不算放行。
        GovernanceException conflict = h.submitExpectReject("req-cf-2", "key-cf", "body-v2-DIFFERENT");
        assertEquals(RejectReason.IDEMPOTENCY_CONFLICT, conflict.getReason());
        assertEquals(1L, h.used());
        assertEquals(1L, h.orders.sideEffectCount());

        // 冲突请求没有污染首次记录：相同内容仍按重放走，且窗口满也不挡。
        GovernanceResult<String> replay = h.submit("req-cf-3", "key-cf", "body-v1");
        assertTrue(replay.replayed());
        assertEquals(first.value(), replay.value());
        assertEquals(1L, h.used());
        assertEquals(1L, h.orders.sideEffectCount());

        // 再次不同内容依旧冲突，结论稳定。
        GovernanceException conflict2 = h.submitExpectReject("req-cf-4", "key-cf", "body-v3-DIFFERENT");
        assertEquals(RejectReason.IDEMPOTENCY_CONFLICT, conflict2.getReason());
        assertEquals(1L, h.used());
    }
}
