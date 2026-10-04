package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.business.OrderService;
import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RequestContext;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceResult;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceStateLock;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import com.github.highcumontoa.tenantrequestgovernancejava.snapshot.SnapshotException;
import com.github.highcumontoa.tenantrequestgovernancejava.snapshot.SnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 快照持久化测试：导出/恢复后限流、幂等、审计状态接续；并发导出一致性；
 * 恢复原子性；损坏/不兼容快照被拒绝且在线状态不受污染。
 * 全部使用可控时钟，结果可重复复现。
 */
class SnapshotPersistenceTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotPersistenceTest.class);
    private static final long WINDOW = 60_000L;

    /** 一套接线好的治理组件；clock 可控，重启场景直接新建一套再 restore。 */
    private static final class Harness {
        final AtomicLong clock;
        final AccessControlService access = new AccessControlService();
        final QuotaService quota;
        final IdempotencyService idempotency = new IdempotencyService();
        final AuditService audit = new AuditService();
        final OrderService orders = new OrderService();
        final GovernanceStateLock stateLock = new GovernanceStateLock();
        final GovernanceFacade facade;
        final SnapshotService snapshots;

        Harness(AtomicLong clock, long quotaLimit) {
            this.clock = clock;
            this.quota = new QuotaService(WINDOW, clock::get);
            access.registerTenant("tenant-a", Set.of("READER", "WRITER", "ADMIN"));
            quota.updateQuota("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER, quotaLimit);
            this.facade = new GovernanceFacade(access, quota, idempotency, audit, orders, stateLock);
            this.snapshots = new SnapshotService(quota, idempotency, audit, stateLock);
        }

        GovernanceResult<String> submit(String requestId, String key, String payload) {
            GovernanceResult<String> r = facade.createOrder(
                    new RequestContext(requestId, "tenant-a", "WRITER", "tenant-a"), key, payload);
            log.info("[{}] decision={} auditSeq={} value={}", requestId,
                    r.replayed() ? "REPLAY" : "ACCEPT", r.auditSeq(), r.value());
            return r;
        }

        GovernanceException submitExpectReject(String requestId, String key, String payload) {
            try {
                facade.createOrder(new RequestContext(requestId, "tenant-a", "WRITER", "tenant-a"), key, payload);
                fail("expected reject for " + requestId);
                return null;
            } catch (GovernanceException ex) {
                long seq = audit.all().stream().filter(e -> requestId.equals(e.requestId()))
                        .mapToLong(AuditEntry::seq).max().orElse(-1);
                log.info("[{}] rejected reason={} auditSeq={} basis={}", requestId,
                        ex.getReason(), seq, ex.getMessage());
                return ex;
            }
        }
    }

    private static void assertAuditContinuous(AuditService audit, String label) {
        List<AuditEntry> all = audit.all();
        long expected = 1L;
        for (AuditEntry e : all) {
            assertEquals(expected, e.seq(), label + ": 审计序号必须从 1 连续递增，无重复/回退/错序");
            expected++;
        }
    }

    @Test
    void restoredStateContinuesQuotaIdempotencyAndAuditAcrossRestart(@TempDir Path dir) {
        AtomicLong clock = new AtomicLong(1_000_000L);
        Harness h1 = new Harness(clock, 3L);

        // 两个首次提交（配额用掉 2/3），其中一个再重放一次（不占配额）。
        GovernanceResult<String> first = h1.submit("req-1", "key-1", "payload-1");
        GovernanceResult<String> replay = h1.submit("req-2", "key-1", "payload-1");
        assertTrue(replay.replayed());
        assertEquals(first.value(), replay.value());
        h1.submit("req-3", "key-2", "payload-2");
        assertEquals(2L, h1.orders.sideEffectCount());
        assertEquals(2L, h1.quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));
        long lastSeqBefore = h1.audit.lastSeq();

        Path file = dir.resolve("snapshot.json");
        h1.snapshots.exportTo(file);
        assertTrue(Files.exists(file));

        // 模拟重启：全新组件 + 同一时钟，从快照接回状态。
        Harness h2 = new Harness(clock, 0L); // 新实例先不配置配额，全部来自快照
        h2.snapshots.restoreFrom(file);

        // 1) 幂等接续：同租户同键重放仍返回首次结果，新实例副作用为 0。
        GovernanceResult<String> replayed = h2.submit("req-4", "key-1", "payload-1");
        assertTrue(replayed.replayed(), "恢复后同键重复提交必须返回首次结果");
        assertEquals(first.value(), replayed.value());
        assertEquals(0L, h2.orders.sideEffectCount(), "恢复后的重放不得再产生业务副作用");

        // 2) 限流接续：未过期窗口已用计数 2/3 继续算，只再放行 1 个。
        assertEquals(2L, h2.quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));
        assertEquals(3L, h2.quota.currentLimit("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));
        h2.submit("req-5", "key-3", "payload-3");
        GovernanceException limited = h2.submitExpectReject("req-6", "key-4", "payload-4");
        assertEquals(com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason.QUOTA_EXCEEDED,
                limited.getReason());

        // 3) 审计接续：序号从快照末尾接着排，整体 1..N 连续。
        assertTrue(h2.audit.lastSeq() > lastSeqBefore);
        assertAuditContinuous(h2.audit, "after-restore");
        log.info("[snapshot] restore ok: lastSeqBefore={} lastSeqAfter={} sideEffects={}",
                lastSeqBefore, h2.audit.lastSeq(), h2.orders.sideEffectCount());
    }

    @Test
    void expiredWindowRollsButLiveWindowCountsAfterRestore(@TempDir Path dir) {
        AtomicLong clock = new AtomicLong(1_000_000L);
        Harness h1 = new Harness(clock, 2L);
        h1.submit("req-w1", "wk-1", "p1");
        h1.submit("req-w2", "wk-2", "p2");
        Path file = dir.resolve("snap.json");
        h1.snapshots.exportTo(file);

        // 窗口未过期：计数接续，第三个请求被拒。
        Harness h2 = new Harness(clock, 0L);
        h2.snapshots.restoreFrom(file);
        h2.submitExpectReject("req-w3", "wk-3", "p3");

        // 时钟越过窗口后恢复：旧计数不应再限制新窗口。
        clock.addAndGet(WINDOW + 1);
        Harness h3 = new Harness(clock, 0L);
        h3.snapshots.restoreFrom(file);
        h3.submit("req-w4", "wk-4", "p4");
        assertEquals(1L, h3.quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER),
                "过期窗口恢复后应按滚动逻辑重新计数");
    }

    @Test
    void concurrentExportYieldsConsistentSnapshot(@TempDir Path dir) throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        Harness h = new Harness(clock, 10_000L);

        int threads = 16, perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        h.submit("req-exp-" + idx + "-" + j, "k-" + idx + "-" + j, "p" + j);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();

        // 请求洪峰期间连续导出多份快照。
        List<Path> exported = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Path f = dir.resolve("snap-" + i + ".json");
            h.snapshots.exportTo(f);
            exported.add(f);
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertNull(failure.get(), "导出期间在线请求不得失败");
        assertEquals(threads * perThread, h.orders.sideEffectCount());

        // 每份快照都必须是某一时刻的一致状态：恢复后三类状态互相吻合。
        for (Path f : exported) {
            Harness check = new Harness(clock, 0L);
            check.snapshots.restoreFrom(f);
            List<AuditEntry> entries = check.audit.all();
            long accepts = entries.stream().filter(e -> "ACCEPT".equals(e.decision())).count();
            assertEquals(entries.size(), check.audit.lastSeq(), "审计序号必须连续无缺口: " + f);
            assertAuditContinuous(check.audit, "restored " + f.getFileName());
            assertEquals(accepts, check.idempotency.exportState().size(),
                    "每条 ACCEPT 必须对应一条幂等结果: " + f);
            assertEquals(accepts, check.quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER),
                    "每条 ACCEPT 必须对应一次配额计数: " + f);
            log.info("[snapshot] {} consistent: entries={} accepts={}", f.getFileName(), entries.size(), accepts);
        }
    }

    @Test
    void concurrentRestoreIsAtomicAndRequestsSeeOldOrNewNotMixed(@TempDir Path dir) throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        Harness h = new Harness(clock, 10_000L);

        // 状态 A：10 个首次提交，导出快照。
        for (int i = 0; i < 10; i++) {
            h.submit("req-a-" + i, "ka-" + i, "pa");
        }
        Path file = dir.resolve("snap-a.json");
        h.snapshots.exportTo(file);
        long seqA = h.audit.lastSeq();

        // 状态 B：再提交 10 个（快照之外的演进）。
        for (int i = 0; i < 10; i++) {
            h.submit("req-b-" + i, "kb-" + i, "pb");
        }

        // 恢复与在线请求并发：恢复线程反复把状态拉回 A，请求线程持续提交新键。
        int requestThreads = 8, perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(requestThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requestThreads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong accepted = new AtomicLong();
        for (int i = 0; i < requestThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        h.submit("req-c-" + idx + "-" + j,
                                "kc-" + idx + "-" + j + "-" + UUID.randomUUID(), "pc");
                        accepted.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 5; i++) {
                    h.snapshots.restoreFrom(file);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertNull(failure.get(), "恢复与请求并发时任何请求都不得出现内部错误");

        // 恢复要么整体生效要么不生效：最终审计流水必须整体连续（从 1 起无重复/回退/错序），
        // 且序号发生器不会发出比已恢复最大序号更小的号。
        assertAuditContinuous(h.audit, "after-concurrent-restore");
        assertTrue(h.audit.lastSeq() >= seqA, "恢复后审计序号不得回退到快照之前");
        log.info("[snapshot] concurrent restore ok: acceptedDuringRestore={} finalSeq={}",
                accepted.get(), h.audit.lastSeq());

        // 恢复后快照 A 里的键重放仍返回首次结果，不再产生新副作用。
        long sideEffectsBefore = h.orders.sideEffectCount();
        GovernanceResult<String> replay = h.submit("req-replay-a", "ka-0", "pa");
        assertTrue(replay.replayed());
        assertEquals(sideEffectsBefore, h.orders.sideEffectCount());
    }

    @Test
    void corruptedTruncatedAndIncompatibleSnapshotsAreRejectedWithoutPollutingState(@TempDir Path dir)
            throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        Harness h = new Harness(clock, 3L);
        GovernanceResult<String> first = h.submit("req-s1", "sk-1", "sp1");
        h.submit("req-s2", "sk-2", "sp2");
        Path good = dir.resolve("good.json");
        h.snapshots.exportTo(good);
        String content = Files.readString(good);

        long countBefore = h.quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER);
        long seqBefore = h.audit.lastSeq();
        long sideEffectsBefore = h.orders.sideEffectCount();

        // 1) 载荷被篡改（校验和不匹配）-> 拒绝。
        int nl = content.indexOf('\n');
        String payload = content.substring(nl + 1);
        char[] tampered = payload.toCharArray();
        tampered[tampered.length / 2] = tampered[tampered.length / 2] == '0' ? '1' : '0';
        Path corrupted = dir.resolve("corrupted.json");
        Files.writeString(corrupted, content.substring(0, nl + 1) + new String(tampered));
        SnapshotException ex1 = assertThrows(SnapshotException.class, () -> h.snapshots.restoreFrom(corrupted));
        log.info("[snapshot] corrupted rejected: {}", ex1.getMessage());

        // 2) 快照写到一半（截断）-> 拒绝。
        Path truncated = dir.resolve("truncated.json");
        Files.writeString(truncated, content.substring(0, content.length() / 2));
        SnapshotException ex2 = assertThrows(SnapshotException.class, () -> h.snapshots.restoreFrom(truncated));
        log.info("[snapshot] truncated rejected: {}", ex2.getMessage());

        // 3) 版本不兼容（只改版本号，载荷完好）-> 拒绝。
        String incompatibleMeta = content.substring(0, nl)
                .replace("\"version\":" + SnapshotService.FORMAT_VERSION, "\"version\":999");
        Path incompatible = dir.resolve("incompatible.json");
        Files.writeString(incompatible, incompatibleMeta + content.substring(nl));
        SnapshotException ex3 = assertThrows(SnapshotException.class, () -> h.snapshots.restoreFrom(incompatible));
        assertTrue(ex3.getMessage().contains("incompatible"), "版本不符必须明确报不兼容");
        log.info("[snapshot] incompatible rejected: {}", ex3.getMessage());

        // 4) 文件不存在 -> 拒绝。
        SnapshotException ex4 = assertThrows(SnapshotException.class,
                () -> h.snapshots.restoreFrom(dir.resolve("missing.json")));
        log.info("[snapshot] missing rejected: {}", ex4.getMessage());

        // 拒绝之后在线状态分毫未动：限流计数、幂等重放、审计序号都按原样继续。
        assertEquals(countBefore, h.quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));
        assertEquals(seqBefore, h.audit.lastSeq());
        GovernanceResult<String> replay = h.submit("req-s3", "sk-1", "sp1");
        assertTrue(replay.replayed());
        assertEquals(first.value(), replay.value());
        assertEquals(sideEffectsBefore, h.orders.sideEffectCount(), "被拒绝的恢复不得触发任何状态变化");
        h.submit("req-s4", "sk-3", "sp3"); // 配额 2->3 仍按原窗口计数
        h.submitExpectReject("req-s5", "sk-4", "sp4"); // 第 4 个超限
        assertAuditContinuous(h.audit, "after-rejected-restores");
    }

    @Test
    void largeSnapshotRestoreKeepsDecisionsFast(@TempDir Path dir) {
        AtomicLong clock = new AtomicLong(1_000_000L);
        Harness h1 = new Harness(clock, 100_000L);

        int keys = 20_000;
        for (int i = 0; i < keys; i++) {
            h1.facade.createOrder(new RequestContext("req-bulk-" + i, "tenant-a", "WRITER", "tenant-a"),
                    "bulk-" + i, "bp");
        }
        Path file = dir.resolve("large.json");
        h1.snapshots.exportTo(file);
        log.info("[snapshot] large snapshot: keys={} size={}B", keys,
                java.util.Optional.ofNullable(file.toFile().length()).orElse(-1L));

        Harness h2 = new Harness(clock, 0L);
        h2.snapshots.restoreFrom(file);
        assertEquals(keys, h2.idempotency.exportState().size());
        assertEquals(keys, h2.quota.currentCount("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER));

        // 恢复后在线判定不能明显劣化：1000 次重放 + 1000 次新提交在宽裕时限内完成。
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            GovernanceResult<String> r = h2.facade.createOrder(
                    new RequestContext("req-fast-r-" + i, "tenant-a", "WRITER", "tenant-a"), "bulk-" + i, "bp");
            assertTrue(r.replayed());
        }
        for (int i = 0; i < 1000; i++) {
            h2.facade.createOrder(new RequestContext("req-fast-n-" + i, "tenant-a", "WRITER", "tenant-a"),
                    "new-" + i, "np");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("[snapshot] 2000 decisions after restoring {} keys took {}ms", keys, elapsedMs);
        assertTrue(elapsedMs < 5_000L, "恢复大快照后判定耗时不应明显劣化, actual=" + elapsedMs + "ms");
        assertEquals(1000L, h2.orders.sideEffectCount(), "重放不得产生副作用，只有新键各执行一次");
        assertAuditContinuous(h2.audit, "after-large-restore");
    }
}
