package com.github.highcumontoa.tenantrequestgovernancejava.snapshot;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.common.SnapshotException;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 治理状态快照编排：在 {@link GovernanceGate} 保护下导出一致状态、
 * 先完整解析校验再在写锁内原子恢复，落盘采用临时文件 + 原子移动 + 校验和。
 *
 * <h2>文件格式（v1）</h2>
 * <pre>
 * GOVERNANCE-SNAPSHOT-V1          # 魔数行
 * sha256:&lt;payload 的十六进制摘要&gt;  # 校验行
 * { ... JSON payload ... }        # 状态本体（见 {@link SnapshotData}）
 * </pre>
 * 任一环节失败（版本不符 / JSON 损坏 / 校验和不匹配 / 窗口参数不一致 / 字段缺失）
 * 都抛 {@link SnapshotException}，且因为状态切换只发生在全部校验通过之后，
 * 当前治理状态不会被残数据污染。
 */
public class SnapshotManager {

    /** 当前快照格式版本；版本不兼容的快照一律拒绝。 */
    public static final int FORMAT_VERSION = 1;

    /** 本实现支持恢复的最低格式版本。 */
    public static final int MIN_SUPPORTED_VERSION = 1;

    static final String MAGIC_PREFIX = "GOVERNANCE-SNAPSHOT-V";
    static final String CHECKSUM_PREFIX = "sha256:";
    static final Path DEFAULT_PATH = Path.of("governance-snapshot.snap");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

    private final GovernanceGate gate;
    private final AccessControlService accessControl;
    private final QuotaService quota;
    private final IdempotencyService idempotency;
    private final AuditService audit;

    public SnapshotManager(GovernanceGate gate,
                           AccessControlService accessControl,
                           QuotaService quota,
                           IdempotencyService idempotency,
                           AuditService audit) {
        this.gate = gate;
        this.accessControl = accessControl;
        this.quota = quota;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    /** 导出当前治理状态到默认路径 {@value #DEFAULT_PATH}。 */
    public SnapshotSummary exportSnapshot() {
        return exportSnapshot(DEFAULT_PATH);
    }

    /**
     * 导出当前治理状态到指定本地文件。
     * <p>状态收集在写锁内一次性完成（全局一致切片，不与在途请求交叉），
     * 磁盘写入在锁外进行，并通过“同目录临时文件 + 原子移动”发布，
     * 因此写盘失败不会破坏已有快照文件，正常请求也不会被磁盘 IO 长时间卡住。
     */
    public SnapshotSummary exportSnapshot(Path target) {
        SnapshotData data = collectSnapshot();
        byte[] payload = writeJson(data);
        byte[] checksum = sha256(payload);

        Path out = target.toAbsolutePath().normalize();
        Path tmp = out.resolveSibling("." + out.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
        try {
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder header = new StringBuilder()
                    .append(MAGIC_PREFIX).append(FORMAT_VERSION).append('\n')
                    .append(CHECKSUM_PREFIX).append(HexFormat.of().formatHex(checksum)).append('\n');
            Files.write(tmp, header.toString().getBytes(StandardCharsets.UTF_8));
            // 以追加方式写入 payload，保证 header 与 payload 在同一次原子发布内。
            Files.write(tmp, payload, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicNotSupported) {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            deleteQuietly(tmp);
            throw new SnapshotException("failed to write snapshot to " + out + ": " + ex.getMessage(), ex);
        }
        return summarize("EXPORT", data, out);
    }

    /**
     * 从本地文件恢复治理状态。
     * <p>流程：读盘 + 校验和 + 版本检查 + JSON 解析 + 各组件字段校验，全部通过后才进入写锁，
     * 在写锁内依次原子换表。任何一步失败都在触碰在线状态之前抛出，服务继续按原状态运行。
     */
    public SnapshotSummary restoreSnapshot(Path source) {
        Path in = source.toAbsolutePath().normalize();
        SnapshotData parsed = readAndValidate(in);

        // 写锁独占：等待所有在途治理请求结束，随后四个组件一起切换，对外不可见中间态。
        gate.writeLock().lock();
        try {
            quota.restoreState(toQuotaStates(parsed));
            idempotency.restoreState(toIdempotentStates(parsed));
            audit.restoreState(new AuditService.SnapshotState(
                    parsed.audit() == null ? 0L : parsed.audit().lastSeq(),
                    toAuditEntries(parsed)));
            accessControl.restoreState(toTenantStates(parsed));
        } finally {
            gate.writeLock().unlock();
        }
        return summarize("RESTORE", parsed, in);
    }

    /** 在写锁内收集四组件同一时刻的状态。 */
    private SnapshotData collectSnapshot() {
        gate.writeLock().lock();
        try {
            List<SnapshotData.QuotaWindowState> quotas = quota.exportState().stream()
                    .map(w -> new SnapshotData.QuotaWindowState(
                            w.tenantId(), w.action(), w.limit(), w.startMillis(), w.count()))
                    .sorted(java.util.Comparator.comparing(SnapshotData.QuotaWindowState::tenantId)
                            .thenComparing(SnapshotData.QuotaWindowState::action))
                    .toList();
            List<SnapshotData.IdempotentRecord> idem = idempotency.exportState().stream()
                    .map(s -> new SnapshotData.IdempotentRecord(
                            s.tenantId(), s.idempotencyKey(), s.requestHash(), s.value()))
                    .sorted(java.util.Comparator.comparing(SnapshotData.IdempotentRecord::tenantId)
                            .thenComparing(SnapshotData.IdempotentRecord::idempotencyKey))
                    .toList();
            AuditService.SnapshotState auditState = audit.exportState();
            List<SnapshotData.AuditRecordState> auditRecords = auditState.entries().stream()
                    .map(e -> new SnapshotData.AuditRecordState(e.seq(), e.requestId(), e.tenantId(),
                            e.action(), e.decision(), e.reason(), e.timestampMillis()))
                    .toList();
            List<SnapshotData.TenantState> tenants = accessControl.exportState().stream()
                    .map(t -> new SnapshotData.TenantState(t.tenantId(), new ArrayList<>(t.roles())))
                    .sorted(java.util.Comparator.comparing(SnapshotData.TenantState::tenantId))
                    .toList();
            return new SnapshotData(FORMAT_VERSION, System.currentTimeMillis(),
                    quota.windowMillis(), quotas, idem,
                    new SnapshotData.AuditState(auditState.lastSeq(), auditRecords), tenants);
        } finally {
            gate.writeLock().unlock();
        }
    }

    /** 读盘、校验魔数/校验和、解析并做全部静态校验；全程不触碰在线状态。 */
    private SnapshotData readAndValidate(Path in) {
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(in);
        } catch (IOException ex) {
            throw new SnapshotException("failed to read snapshot " + in + ": " + ex.getMessage(), ex);
        }
        String content = new String(fileBytes, StandardCharsets.UTF_8);
        int firstNl = content.indexOf('\n');
        int secondNl = content.indexOf('\n', firstNl + 1);
        if (firstNl <= 0 || secondNl < 0) {
            throw new SnapshotException("snapshot file is malformed (missing header lines)");
        }
        String magic = content.substring(0, firstNl).trim();
        String checksumLine = content.substring(firstNl + 1, secondNl).trim();
        String payloadText = content.substring(secondNl + 1);
        if (!magic.startsWith(MAGIC_PREFIX)) {
            throw new SnapshotException("not a governance snapshot file: bad magic header");
        }
        int headerVersion;
        try {
            headerVersion = Integer.parseInt(magic.substring(MAGIC_PREFIX.length()).trim());
        } catch (NumberFormatException ex) {
            throw new SnapshotException("snapshot header version is not a number: " + magic);
        }
        if (headerVersion != FORMAT_VERSION) {
            throw new SnapshotException("snapshot format version " + headerVersion
                    + " is not supported by this build (supports "
                    + MIN_SUPPORTED_VERSION + ".." + FORMAT_VERSION + "); restore rejected");
        }
        if (!checksumLine.startsWith(CHECKSUM_PREFIX)) {
            throw new SnapshotException("snapshot checksum line is malformed; restore rejected");
        }
        String expectedHex = checksumLine.substring(CHECKSUM_PREFIX.length());
        byte[] payload = payloadText.getBytes(StandardCharsets.UTF_8);
        String actualHex = HexFormat.of().formatHex(sha256(payload));
        if (!constantTimeEquals(expectedHex, actualHex)) {
            throw new SnapshotException("snapshot checksum mismatch: file is corrupted or truncated; restore rejected");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (IOException ex) {
            throw new SnapshotException("snapshot payload is not valid JSON: " + ex.getMessage(), ex);
        }
        if (root == null || !root.isObject()) {
            throw new SnapshotException("snapshot payload root must be a JSON object");
        }
        int dataVersion = root.path("formatVersion").asInt(-1);
        if (dataVersion < MIN_SUPPORTED_VERSION || dataVersion > FORMAT_VERSION) {
            throw new SnapshotException("snapshot payload formatVersion=" + dataVersion
                    + " is incompatible with supported range "
                    + MIN_SUPPORTED_VERSION + ".." + FORMAT_VERSION);
        }
        SnapshotData data;
        try {
            data = MAPPER.treeToValue(root, SnapshotData.class);
        } catch (IOException ex) {
            throw new SnapshotException("snapshot payload failed structural mapping: " + ex.getMessage(), ex);
        }
        validatePayload(data);
        return data;
    }

    /** 语义级字段校验：任何空字段、非法计数、审计断号等都在此拒绝（在线状态尚未触碰）。 */
    private void validatePayload(SnapshotData d) {
        if (d.quotas() == null || d.idempotentResults() == null
                || d.audit() == null || d.tenants() == null) {
            throw new SnapshotException("snapshot payload is missing one of sections "
                    + "(quotas/idempotentResults/audit/tenants)");
        }
        quota.validateWindowMillis(d.windowMillis());
        for (SnapshotData.QuotaWindowState q : d.quotas()) {
            if (blank(q.tenantId()) || blank(q.action())) {
                throw new SnapshotException("snapshot quota entry has blank tenantId/action");
            }
            if (q.limit() < 0) {
                throw new SnapshotException("snapshot quota for " + q.tenantId() + "/" + q.action()
                        + " carries negative limit");
            }
            if (q.count() < 0 || q.startMillis() < 0) {
                throw new SnapshotException("snapshot quota for " + q.tenantId() + "/" + q.action()
                        + " carries negative count/startMillis");
            }
        }
        for (SnapshotData.IdempotentRecord r : d.idempotentResults()) {
            if (blank(r.tenantId()) || blank(r.idempotencyKey())) {
                throw new SnapshotException("snapshot idempotent record has blank tenantId/key");
            }
            if (blank(r.requestHash())) {
                throw new SnapshotException("snapshot idempotent record for key "
                        + r.idempotencyKey() + " has missing requestHash");
            }
            if (r.value() == null) {
                throw new SnapshotException("snapshot idempotent record for key "
                        + r.idempotencyKey() + " has null result value");
            }
        }
        List<AuditEntry> entries = toAuditEntries(d);
        audit.validateSnapshot(new AuditService.SnapshotState(d.audit().lastSeq(), entries));
        // 租户角色在恢复入口再校验一遍，这里先保证结构存在。
        for (SnapshotData.TenantState t : d.tenants()) {
            if (blank(t.tenantId()) || t.roles() == null || t.roles().isEmpty()) {
                throw new SnapshotException("snapshot tenant section has blank tenant or empty roles");
            }
        }
    }

    private List<QuotaService.WindowState> toQuotaStates(SnapshotData d) {
        List<QuotaService.WindowState> out = new ArrayList<>(d.quotas().size());
        for (SnapshotData.QuotaWindowState q : d.quotas()) {
            out.add(new QuotaService.WindowState(q.tenantId(), q.action(), q.limit(),
                    q.startMillis(), q.count()));
        }
        return out;
    }

    private List<IdempotencyService.CompletedState> toIdempotentStates(SnapshotData d) {
        List<IdempotencyService.CompletedState> out = new ArrayList<>(d.idempotentResults().size());
        for (SnapshotData.IdempotentRecord r : d.idempotentResults()) {
            out.add(new IdempotencyService.CompletedState(
                    r.tenantId(), r.idempotencyKey(), r.requestHash(), r.value()));
        }
        return out;
    }

    private List<AuditEntry> toAuditEntries(SnapshotData d) {
        List<AuditEntry> out = new ArrayList<>(d.audit().entries().size());
        for (SnapshotData.AuditRecordState e : d.audit().entries()) {
            out.add(new AuditEntry(e.seq(), e.requestId(), e.tenantId(), e.action(),
                    e.decision(), e.reason(), e.timestampMillis()));
        }
        return out;
    }

    private List<AccessControlService.TenantState> toTenantStates(SnapshotData d) {
        List<AccessControlService.TenantState> out = new ArrayList<>(d.tenants().size());
        for (SnapshotData.TenantState t : d.tenants()) {
            out.add(new AccessControlService.TenantState(t.tenantId(), Set.copyOf(t.roles())));
        }
        return out;
    }

    private static byte[] writeJson(SnapshotData data) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
        } catch (IOException ex) {
            throw new SnapshotException("failed to serialize snapshot: " + ex.getMessage(), ex);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private SnapshotSummary summarize(String operation, SnapshotData d, Path path) {
        return new SnapshotSummary(operation, d.formatVersion(), d.takenAtMillis(), path,
                d.quotas().size(), d.idempotentResults().size(),
                d.audit().entries().size(), d.tenants().size());
    }

    /** 一次导出 / 恢复动作的结果摘要。 */
    public record SnapshotSummary(String operation,
                                  int formatVersion,
                                  long takenAtMillis,
                                  Path path,
                                  int quotaWindows,
                                  int idempotentResults,
                                  int auditEntries,
                                  int tenants) {
    }
}
