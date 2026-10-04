package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.business.OrderService;
import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RequestContext;
import com.github.highcumontoa.tenantrequestgovernancejava.common.SnapshotException;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceResult;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import com.github.highcumontoa.tenantrequestgovernancejava.snapshot.GovernanceGate;
import com.github.highcumontoa.tenantrequestgovernancejava.snapshot.SnapshotManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 快照治理行为测试：
 * <ol>
 *   <li>导出 -> 重启（全新组件）-> 恢复：配额计数接着算、幂等返回首次结果且无重复副作用、
 *       审计序号从已有序号往后连续；</li>
 *   <li>恢复时已过期窗口计数被折叠，不拖垮在线判定；</li>
 *   <li>并发导出 / 恢复时限流结论、幂等重放、审计序号连续稳定；</li>
 *   <li>版本不符 / 校验和损坏 / 截断 / 非法内容被明确拒绝，现有状态不受污染。</li>
 * </ol>
 */
class SnapshotGovernanceTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotGovernanceTest.class);
    private static final String ACTION = GovernanceFacade.ACTION_CREATE_ORDER;

    /** 一组装配好的治理组件（含共享并发门），便于模拟“重启前/重启后”两套世界。 */
    private record World(GovernanceGate gate,
                         AccessControlService access,
                         QuotaService quota,
                         IdempotencyService idempotency,
                         AuditService audit,
                         OrderService orders,
                         GovernanceFacade facade,
                         SnapshotManager snapshots,
                         AtomicLong clock) {
    }

    private World newWorld(long windowMillis, long startTime) {
        AtomicLong clock = new AtomicLong(startTime);
        GovernanceGate gate = new GovernanceGate();
        AccessControlService access = new AccessControlService();
        access.registerTenant("tenant-a", Set.of("READER", "WRITER", "ADMIN"));
        access.registerTenant("tenant-b", Set.of("READER", "WRITER", "ADMIN"));
        QuotaService quota = new QuotaService(windowMillis, clock::get);
        quota.updateQuota("tenant-a", ACTION, 3L);
        quota.updateQuota("tenant-b", ACTION, 5L);
        IdempotencyService idempotency = new IdempotencyService();
        AuditService audit = new AuditService();
        OrderService orders = new OrderService();
        GovernanceFacade facade = new GovernanceFacade(access, quota, idempotency, audit, orders, gate);
        SnapshotManager snapshots = new SnapshotManager(gate, access, quota, idempotency, audit);
        return new World(gate, access, quota, idempotency, audit, orders, facade, snapshots, clock);
    }

    private RequestContext ctx(World w, String tenant, String requestId) {
        return new RequestContext(requestId, tenant, "WRITER", tenant);
    }

    private void logAudit(AuditService audit, String prefix) {
        for (AuditEntry e : audit.all()) {
            log.info("{} [{}] seq={} decision={} basis={}", prefix, e.requestId(), e.seq(), e.decision(), e.reason());
        }
    }

    @Test
    void exportThenRestoreKeepsQuotaIdempotencyAndAuditContinuity(@TempDir Path dir) {
        // ---- 重启前：2 次首次提交 + 1 次幂等重放 ----
        World before = newWorld(60_000L, 1_000L);
        GovernanceResult<String> r1 = before.facade().createOrder(ctx(before, "tenant-a", "req-pre-1"), "key-x", "body");
        GovernanceResult<String> r2 = before.facade().createOrder(ctx(before, "tenant-a", "req-pre-2"), "key-y", "body2");
        GovernanceResult<String> replay = before.facade().createOrder(ctx(before, "tenant-a", "req-pre-replay"), "key-x", "body");
        assertTrue(replay.replayed());
        assertEquals(r1.value(), replay.value());
        // 重放不占配额：窗口用量 = 2（两次首次）。
        assertEquals(2L, before.quota().currentCount("tenant-a", ACTION));
        long seqBefore = before.audit().currentSeq();
        log.info("[req-pre-*] used quota=2/3 auditSeq before snapshot={}", seqBefore);
        logAudit(before.audit(), "PRE");

        Path snap = dir.resolve("snap-a.snap");
        SnapshotManager.SnapshotSummary exported = before.snapshots().exportSnapshot(snap);
        log.info("export snapshot: v={} quotas={} idempotent={} audit={} tenants={} file={}",
                exported.formatVersion(), exported.quotaWindows(), exported.idempotentResults(),
                exported.auditEntries(), exported.tenants(), snap);

        // ---- “重启”：全部组件重新 new，模拟空内存的新进程 ----
        World after = newWorld(60_000L, 1_000L);
        SnapshotManager.SnapshotSummary restored = after.snapshots().restoreSnapshot(snap);
        log.info("restore snapshot: quotas={} idempotent={} audit={} tenants={}",
                restored.quotaWindows(), restored.idempotentResults(), restored.auditEntries(), restored.tenants());

        // 1) 窗口计数接回：已用 2，配额 3，再来 1 次首次成功，第 2 次（总计第 4 次）限流。
        assertEquals(2L, after.quota().currentCount("tenant-a", ACTION), "恢复时未过期窗口计数必须接回");
        assertEquals(3L, after.quota().currentLimit("tenant-a", ACTION), "配额上限必须接回");
        GovernanceResult<String> r3 = after.facade().createOrder(
                ctx(after, "tenant-a", "req-post-1"), "key-z", "body3");
        assertFalse(r3.replayed());
        log.info("[req-post-1] decision=ACCEPT auditSeq={} basis=quota ok (3/3), restored window", r3.auditSeq());
        GovernanceException over = assertThrows(GovernanceException.class,
                () -> after.facade().createOrder(ctx(after, "tenant-a", "req-post-over"), "key-w", "body4"));
        AuditEntry overEntry = after.audit().all().stream()
                .filter(e -> "req-post-over".equals(e.requestId())).findFirst().orElseThrow();
        log.info("[req-post-over] rejected reason={} basis={} auditSeq={}",
                over.getReason(), over.getMessage(), overEntry.seq());
        assertEquals(RejectReason.QUOTA_EXCEEDED, over.getReason());

        // 2) 幂等接回：同租户同键返回首次结果；重放回退预占，窗口仍为满额 3/3，且无新副作用。
        long sideEffectsBeforeReplay = after.orders().sideEffectCount();
        GovernanceResult<String> replayedAfter = after.facade().createOrder(
                ctx(after, "tenant-a", "req-post-replay"), "key-x", "body");
        log.info("[req-post-replay] decision=REPLAY orderId={} auditSeq={} basis=restored idempotent result",
                replayedAfter.value(), replayedAfter.auditSeq());
        assertTrue(replayedAfter.replayed());
        assertEquals(r1.value(), replayedAfter.value(), "必须返回快照前第一次的结果");
        assertEquals(sideEffectsBeforeReplay, after.orders().sideEffectCount(),
                "重放不得在恢复后再次执行业务副作用");
        assertEquals(3L, after.quota().currentCount("tenant-a", ACTION), "重放不消耗业务配额");

        // 同键不同内容仍然冲突。
        GovernanceException conflict = assertThrows(GovernanceException.class,
                () -> after.facade().createOrder(ctx(after, "tenant-a", "req-post-conflict"), "key-x", "DIFFERENT"));
        assertEquals(RejectReason.IDEMPOTENCY_CONFLICT, conflict.getReason());
        log.info("[req-post-conflict] rejected reason={}", conflict.getReason());

        // 3) 审计序号接回：恢复后所有新序号严格从 seqBefore+1 连续。
        logAudit(after.audit(), "POST");
        assertEquals(seqBefore, restored.auditEntries(), "恢复的流水条数等于重启前序号");
        long expected = 1L;
        for (AuditEntry e : after.audit().all()) {
            assertEquals(expected, e.seq(), "审计序号不得重复、回退或断号");
            expected++;
        }
        assertEquals(after.audit().all().size() + 1L, expected, "全部条目按 1..N 连续排列");
    }

    @Test
    void expiredWindowCountIsCollapsedOnRestore(@TempDir Path dir) {
        World before = newWorld(60_000L, 1_000L);
        for (int i = 0; i < 3; i++) {
            before.facade().createOrder(ctx(before, "tenant-a", "req-old-" + i), UUID.randomUUID().toString(), "p");
        }
        assertEquals(3L, before.quota().currentCount("tenant-a", ACTION));
        Path snap = dir.resolve("snap-old.snap");
        before.snapshots().exportSnapshot(snap);

        // 新进程的时钟已推进到窗口过期之后。
        World after = newWorld(60_000L, 70_000L);
        after.snapshots().restoreSnapshot(snap);
        // 旧窗口在恢复时折叠为 0，判定响应不因接历史数据而劣化，新窗口重新计数。
        GovernanceResult<String> r = after.facade().createOrder(
                ctx(after, "tenant-a", "req-new-1"), UUID.randomUUID().toString(), "p");
        log.info("[req-new-1] decision=ACCEPT auditSeq={} basis=expired window folded to 0 on restore", r.auditSeq());
        assertEquals(1L, after.quota().currentCount("tenant-a", ACTION),
                "恢复时已过期的窗口计数应折叠为 0，上限保留");
        assertEquals(3L, after.quota().currentLimit("tenant-a", ACTION));
    }

    @Test
    void corruptedOrIncompatibleSnapshotsAreRejectedAndStateUntouched(@TempDir Path dir) throws Exception {
        World before = newWorld(60_000L, 1_000L);
        before.facade().createOrder(ctx(before, "tenant-a", "req-ok-1"), "key-1", "body");
        before.facade().createOrder(ctx(before, "tenant-a", "req-ok-2"), "key-2", "body");
        Path good = dir.resolve("good.snap");
        before.snapshots().exportSnapshot(good);

        // 1) 篡改 payload（改一个字符）但校验行不动 -> 校验和不符拒绝。
        Path tampered = dir.resolve("tampered.snap");
        byte[] bytes = Files.readAllBytes(good);
        int payloadStart = payloadStart(bytes);
        bytes[payloadStart + 40] = (byte) (bytes[payloadStart + 40] == 'a' ? 'b' : 'a');
        Files.write(tampered, bytes);

        // 2) 截断：只保留头部与部分 payload -> 校验和不符拒绝。
        Path truncated = dir.resolve("truncated.snap");
        Files.write(truncated, java.util.Arrays.copyOfRange(bytes, 0, bytes.length / 2));

        // 3) 魔数/版本不支持：把头部 V1 改成 V9。
        Path badVersion = dir.resolve("bad-version.snap");
        String text = Files.readString(good, java.nio.charset.StandardCharsets.UTF_8);
        String reversioned = text.replaceFirst(MAGIC, MAGIC + "9");
        Files.writeString(badVersion, reversioned, java.nio.charset.StandardCharsets.UTF_8);

        // 4) payload 内 formatVersion 不兼容 -> 版本检查拒绝（校验和需重算才会走到版本检查）。
        Path badPayloadVersion = dir.resolve("bad-payload-version.snap");
        writeWithChecksum(badPayloadVersion,
                Files.readString(good).substring(payloadStart(bytes))
                        .replace("\"formatVersion\" : 1", "\"formatVersion\" : 9")
                        .replace("\"formatVersion\":1", "\"formatVersion\":9"));

        for (Path p : List.of(tampered, truncated, badVersion, badPayloadVersion)) {
            SnapshotException ex = assertThrows(SnapshotException.class,
                    () -> before.snapshots().restoreSnapshot(p),
                    "损坏/不兼容快照必须被拒绝: " + p.getFileName());
            log.info("[reject-{}] refused: {}", p.getFileName(), ex.getMessage());
        }

        // 拒绝之后当前治理状态原封不动：计数、幂等结果、审计序号全部保持。
        assertEquals(2L, before.quota().currentCount("tenant-a", ACTION));
        var replay = before.facade().createOrder(ctx(before, "tenant-a", "req-after-reject"), "key-1", "body");
        assertTrue(replay.replayed(), "拒绝坏快照后，既有幂等结果仍可正常重放");
        assertEquals(2L, before.quota().currentCount("tenant-a", ACTION), "重放不占配额，计数不变");
        long expectedSeq = 1L;
        for (AuditEntry e : before.audit().all()) {
            assertEquals(expectedSeq, e.seq(), "坏快照不得污染审计序号");
            expectedSeq++;
        }
        log.info("[state-after-reject] quotaCount=2 idempotentReplay=true auditEntries={} seqContiguous=true",
                before.audit().all().size());
    }

    @Test
    void incompatibleWindowMillisIsRejected(@TempDir Path dir) throws Exception {
        World before = newWorld(60_000L, 1_000L);
        before.facade().createOrder(ctx(before, "tenant-a", "req-x"), "k", "p");
        Path snap = dir.resolve("w60.snap");
        before.snapshots().exportSnapshot(snap);

        // 新进程窗口长度不同（例如配置变更为 30s）-> 语义不兼容，拒绝恢复。
        World other = newWorld(30_000L, 1_000L);
        SnapshotException ex = assertThrows(SnapshotException.class,
                () -> other.snapshots().restoreSnapshot(snap));
        log.info("[reject-window] refused: {}", ex.getMessage());
        assertTrue(ex.getMessage().contains("windowMillis"));
        // 新进程仍是演示初始状态，未被污染：快照中的历史幂等键不存在（走首次执行）。
        var fresh = other.facade().createOrder(ctx(other, "tenant-a", "req-fresh"), "k", "p");
        assertFalse(fresh.replayed(), "被拒绝的快照不得写入任何幂等结果");
        assertEquals(1L, other.quota().currentCount("tenant-a", ACTION));
    }

    @Test
    void failedWriteDoesNotDestroyExistingSnapshot(@TempDir Path dir) {
        World before = newWorld(60_000L, 1_000L);
        before.facade().createOrder(ctx(before, "tenant-a", "req-v1"), "k1", "p");
        Path good = dir.resolve("good.snap");
        before.snapshots().exportSnapshot(good);
        byte[] original;
        try {
            original = Files.readAllBytes(good);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 目标路径的父目录是一个普通文件 -> 无法在其下创建临时文件，导出失败。
        Path blocker = dir.resolve("not-a-dir");
        try {
            Files.writeString(blocker, "x");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Path impossible = blocker.resolve("snapshot.snap");
        SnapshotException ex = assertThrows(SnapshotException.class,
                () -> before.snapshots().exportSnapshot(impossible));
        log.info("[export-fail] refused: {}", ex.getMessage());

        // 旧快照文件内容原样保留，仍可成功恢复。
        byte[] after;
        try {
            after = Files.readAllBytes(good);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertArrayEquals(original, after, "写盘失败不得破坏已有快照");
        World afterWorld = newWorld(60_000L, 1_000L);
        var restored = afterWorld.snapshots().restoreSnapshot(good);
        assertEquals(1, restored.auditEntries());
        log.info("[old-snapshot-intact] restore still works, auditEntries={}", restored.auditEntries());
    }

    private static final String MAGIC = "GOVERNANCE-SNAPSHOT-V";

    private static int payloadStart(byte[] fileBytes) {
        int nl = 0;
        int seen = 0;
        for (int i = 0; i < fileBytes.length; i++) {
            if (fileBytes[i] == '\n') {
                seen++;
                if (seen == 2) {
                    nl = i + 1;
                    break;
                }
            }
        }
        return nl;
    }

    /** 用合法头部 + 新校验和重写 payload（用于构造“校验和合法但内容版本不兼容”的快照）。 */
    private static void writeWithChecksum(Path target, String payload) throws Exception {
        byte[] payloadBytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(payloadBytes);
        String header = MAGIC + "1\nsha256:" + java.util.HexFormat.of().formatHex(digest) + "\n";
        try (java.io.OutputStream os = Files.newOutputStream(target,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE)) {
            os.write(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.write(payloadBytes);
        }
    }

    @Test
    void concurrentExportsSeeConsistentStateAndQuotaConclusionsStable(@TempDir Path dir) throws Exception {
        World world = newWorld(60_000L, 1_000L);
        int writers = 24;
        int requestsEach = 20;
        int exporters = 6;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(12);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(writers + exporters);
        java.util.concurrent.ConcurrentLinkedQueue<String> errors = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.atomic.AtomicInteger accepts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger replays = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejects = new java.util.concurrent.atomic.AtomicInteger();

        // 写线程：混合首次提交与对同一键的重放。
        for (int w = 0; w < writers; w++) {
            final int idx = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < requestsEach; i++) {
                        String rid = "req-ce-" + idx + "-" + i;
                        boolean repeatKey = i % 3 == 0;
                        String key = repeatKey ? "shared-key" : "key-" + idx + "-" + i;
                        try {
                            var r = world.facade().createOrder(ctx(world, "tenant-a", rid), key, "body");
                            if (r.replayed()) {
                                replays.incrementAndGet();
                            } else {
                                accepts.incrementAndGet();
                            }
                        } catch (GovernanceException e) {
                            if (e.getReason() == RejectReason.QUOTA_EXCEEDED) {
                                rejects.incrementAndGet();
                            } else {
                                errors.add(rid + ":" + e.getReason());
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        // 导出线程：与写并发执行，每次导出的快照必须通过完整校验（版本/校验和/审计连续性）。
        for (int e = 0; e < exporters; e++) {
            final int idx = e;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 4; i++) {
                        Path p = dir.resolve("ce-" + idx + "-" + i + ".snap");
                        var s = world.snapshots().exportSnapshot(p);
                        // 自洽性：恢复到一个独立世界必须成功（间接证明导出内容始终一致、不半新半旧）。
                        World probe = newWorld(60_000L, 1_000L);
                        probe.snapshots().restoreSnapshot(p);
                        long probeSeq = probe.audit().currentSeq();
                        assertEquals(probe.audit().all().size(), probeSeq,
                                "并发导出的快照审计必须自洽连续: " + p.getFileName());
                        if (i == 0) {
                            log.info("[export-{}] consistent snapshot quotas={} idem={} audit={}",
                                    idx, s.quotaWindows(), s.idempotentResults(), s.auditEntries());
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();
        log.info("[concurrent-export] accepts={} replays={} quotaRejects={} errors={} finalAuditSeq={} windowCount={}",
                accepts.get(), replays.get(), rejects.get(), errors.size(),
                world.audit().currentSeq(), world.quota().currentCount("tenant-a", ACTION));

        assertTrue(errors.isEmpty(), "不得出现限流以外的异常: " + errors);
        // 配额守恒：首次接受数 = 重放预占回退后的最终窗口用量（配额上限 3）。
        long finalCount = world.quota().currentCount("tenant-a", ACTION);
        assertEquals(Math.min(accepts.get(), 3L), finalCount, "窗口计数必须与首次接受数守恒");
        // 审计全局连续：1..N 无缺口。
        long seq = 1L;
        for (AuditEntry en : world.audit().all()) {
            assertEquals(seq, en.seq(), "并发导出/写入期间审计序号不得断号");
            seq++;
        }
    }

    @Test
    void concurrentRestoreIsAllOrNothingAndAuditContinues(@TempDir Path dir) throws Exception {
        World before = newWorld(60_000L, 1_000L);
        // 快照里预置：配额用掉 2、2 个幂等结果、3 条审计。
        before.facade().createOrder(ctx(before, "tenant-a", "req-s-1"), "rk-1", "b");
        before.facade().createOrder(ctx(before, "tenant-a", "req-s-2"), "rk-2", "b");
        before.facade().createOrder(ctx(before, "tenant-a", "req-s-rep"), "rk-1", "b");
        Path snap = dir.resolve("restore.snap");
        before.snapshots().exportSnapshot(snap);

        for (int round = 0; round < 5; round++) {
            final int roundNo = round;
            World target = newWorld(60_000L, 1_000L);
            // 目标世界在恢复前制造一些“现状”流量。
            target.facade().createOrder(ctx(target, "tenant-a", "req-preexist-" + roundNo),
                    "old-" + roundNo, "x");

            int threads = 12;
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
            java.util.concurrent.ConcurrentLinkedQueue<String> problems = new java.util.concurrent.ConcurrentLinkedQueue<>();
            java.util.concurrent.atomic.AtomicInteger restoreOk = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger reqOk = new java.util.concurrent.atomic.AtomicInteger();

            // 1 个恢复线程。
            pool.submit(() -> {
                try {
                    start.await();
                    target.snapshots().restoreSnapshot(snap);
                    restoreOk.incrementAndGet();
                } catch (Exception e) {
                    problems.add("restore:" + e);
                } finally {
                    done.countDown();
                }
            });
            // 其余线程在恢复同时打请求（唯一新键为主，避免互相限流干扰）。
            for (int t = 0; t < threads - 1; t++) {
                final int ti = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 5; i++) {
                            try {
                                target.facade().createOrder(ctx(target, "tenant-a",
                                        "req-cr-" + roundNo + "-" + ti + "-" + i), UUID.randomUUID().toString(), "b");
                                reqOk.incrementAndGet();
                            } catch (GovernanceException e) {
                                // 恢复窗口内可能恰好打满配额 -> 合法限流，不算问题。
                                if (e.getReason() != RejectReason.QUOTA_EXCEEDED) {
                                    problems.add(e.getReason().name());
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
            pool.shutdown();

            assertTrue(problems.isEmpty(), "恢复期间不得出现非限流异常: " + problems);
            assertEquals(1, restoreOk.get(), "恢复必须恰好整体生效一次");

            // 恢复后幂等结果可用：rk-1 重放返回快照首次订单号、无新副作用。
            var replay = target.facade().createOrder(
                    ctx(target, "tenant-a", "req-cr-replay-" + roundNo), "rk-1", "b");
            assertTrue(replay.replayed());
            // 审计：无论恢复前后请求如何交错，最终序号必须是 1..N 严格连续。
            long expected = 1L;
            for (AuditEntry en : target.audit().all()) {
                if (en.seq() != expected) {
                    problems.add("seq gap expected=" + expected + " got=" + en.seq());
                }
                expected++;
            }
            assertTrue(problems.isEmpty(), "恢复后审计序号必须连续: " + problems);
            log.info("[concurrent-restore round={}] reqOk={} finalAuditSeq={} replayAuditSeq={} contiguous=true",
                    roundNo, reqOk.get(), target.audit().currentSeq(), replay.auditSeq());
        }
    }

    @Test
    void largeRestoreKeepsOnlineDecisionLatencyLow(@TempDir Path dir) {
        World before = newWorld(60_000L, 1_000L);
        // 制造“大量历史状态”：约 1.2 万条幂等结果 + 等量审计（控制在单测可接受的体量）。
        int historical = 12_000;
        before.quota().updateQuota("tenant-a", ACTION, historical / 2 + 10);
        before.quota().updateQuota("tenant-b", ACTION, historical / 2 + 10);
        for (int i = 0; i < historical; i++) {
            String tenant = i % 2 == 0 ? "tenant-a" : "tenant-b";
            before.facade().createOrder(
                    new RequestContext("req-bulk-" + i, tenant, "WRITER", tenant),
                    "bulk-" + i, "p");
        }
        Path snap = dir.resolve("large.snap");
        var exported = before.snapshots().exportSnapshot(snap);
        log.info("[large-export] idempotent={} audit={} quotas={}",
                exported.idempotentResults(), exported.auditEntries(), exported.quotaWindows());

        World after = newWorld(60_000L, 1_000L);
        long t0 = System.nanoTime();
        after.snapshots().restoreSnapshot(snap);
        long restoreMillis = (System.nanoTime() - t0) / 1_000_000;

        // 恢复后在线判定仍为 O(1)：哈希命中重放与新键首次执行各测 200 次，取平均与 p95。
        java.util.List<Long> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long s = System.nanoTime();
            after.facade().createOrder(new RequestContext("req-lat-replay-" + i, "tenant-a", "WRITER", "tenant-a"),
                    "bulk-" + (i * 2), "p"); // 命中历史键 -> 重放
            samples.add(System.nanoTime() - s);
        }
        for (int i = 0; i < 200; i++) {
            long s = System.nanoTime();
            try {
                after.facade().createOrder(new RequestContext("req-lat-new-" + i, "tenant-a", "WRITER", "tenant-a"),
                        "brand-new-" + UUID.randomUUID(), "p");
            } catch (GovernanceException expectedQuota) {
                // tenant-a 配额 3，历史已占 3（偶数键落在 a），新键会限流；计时仍有效。
                assertEquals(RejectReason.QUOTA_EXCEEDED, expectedQuota.getReason());
            }
            samples.add(System.nanoTime() - s);
        }
        java.util.Collections.sort(samples);
        double avgMicros = samples.stream().mapToLong(Long::longValue).average().orElseThrow() / 1_000.0;
        long p95Micros = samples.get((int) (samples.size() * 0.95)) / 1_000;
        log.info("[large-restore] restore={}ms onlineDecision avg={}us p95={}us (history={})",
                restoreMillis, String.format("%.1f", avgMicros), p95Micros, historical);
        // 哈希结构在线判定应为微秒到数百微秒级；给出宽松上限避免 CI 抖动误报。
        assertTrue(p95Micros < 50_000L, "恢复大量历史后在线判定 p95 不应明显劣化");
    }
}
