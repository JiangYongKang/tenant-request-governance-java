package com.github.highcumontoa.tenantrequestgovernancejava.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceStateLock;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 治理状态快照编排：把限流、幂等、审计状态整体导出到本地文件 / 从本地文件整体恢复。
 *
 * <p><b>导出</b>：在全局写锁内只做内存态的一次性拷贝（纯内存、耗时极短，
 * 在线请求只被短暂阻塞），序列化与落盘在锁外完成；因此导出结果是
 * “某一时刻的一致状态”，不会拼出一半旧值一半新值。
 *
 * <p><b>落盘</b>：先写同目录临时文件再原子 rename，避免快照写到一半失败
 * 留下半个正式文件；文件首行为元信息（版本/时间戳/校验和），其余为载荷 JSON，
 * 校验和覆盖载荷字节，截断或篡改都会被发现。
 *
 * <p><b>恢复</b>：全部解析与校验（版本、校验和、结构、审计序号连续性、
 * 幂等结果可反序列化）都在加锁前完成；校验通过后才在全局写锁内一次性换入
 * 三类状态——要么整体生效、要么整体不生效，拒绝时在线状态不被污染。
 */
public class SnapshotService {

    /** 当前快照格式版本；只接受完全一致的版本号。 */
    public static final int FORMAT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 快照文件首行的元信息。 */
    record Meta(int version, long createdAtMillis, String checksum) {
    }

    private final QuotaService quota;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final GovernanceStateLock stateLock;

    public SnapshotService(QuotaService quota, IdempotencyService idempotency,
                           AuditService audit, GovernanceStateLock stateLock) {
        this.quota = quota;
        this.idempotency = idempotency;
        this.audit = audit;
        this.stateLock = stateLock;
    }

    /**
     * 把当前治理状态导出为一份本地快照文件。
     * 与并发请求共处：写锁只覆盖内存拷贝，文件 IO 在锁外进行。
     */
    public void exportTo(Path file) {
        SnapshotPayload payload = stateLock.withWriteLock(() -> new SnapshotPayload(
                quota.exportState(), idempotency.exportState(), audit.lastSeq(), audit.all()));
        writeAtomically(file, payload);
    }

    /**
     * 从本地快照文件恢复治理状态。
     * 版本不符、校验和不对、内容损坏或结构非法时抛 {@link SnapshotException}，
     * 当前在线状态保持原样，继续正常限流/幂等/审计。
     */
    public void restoreFrom(Path file) {
        SnapshotPayload payload = readAndValidate(file);
        stateLock.withWriteLock(() -> {
            quota.importState(payload.quotas());
            idempotency.importState(payload.idempotency());
            audit.importState(payload.auditLastSeq(), payload.auditEntries());
            return null;
        });
    }

    // ---- 导出侧 ----

    private void writeAtomically(Path file, SnapshotPayload payload) {
        String payloadJson = toJson(payload);
        Meta meta = new Meta(FORMAT_VERSION, System.currentTimeMillis(),
                sha256(payloadJson.getBytes(StandardCharsets.UTF_8)));
        String content = toJson(meta) + "\n" + payloadJson;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SnapshotException("failed to write snapshot to " + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响导出结果
            }
        }
    }

    // ---- 恢复侧：先完整校验，再加锁换入 ----

    private SnapshotPayload readAndValidate(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SnapshotException("snapshot file not readable: " + file, e);
        }
        int nl = content.indexOf('\n');
        if (nl <= 0) {
            throw new SnapshotException("corrupted snapshot: missing meta line");
        }
        Meta meta = parse(content.substring(0, nl), Meta.class, "meta");
        if (meta.version() != FORMAT_VERSION) {
            throw new SnapshotException("incompatible snapshot version " + meta.version()
                    + " (supported: " + FORMAT_VERSION + ")");
        }
        String payloadJson = content.substring(nl + 1);
        String actual = sha256(payloadJson.getBytes(StandardCharsets.UTF_8));
        if (!actual.equals(meta.checksum())) {
            throw new SnapshotException("corrupted snapshot: checksum mismatch");
        }
        SnapshotPayload payload = parse(payloadJson, SnapshotPayload.class, "payload");
        validateStructure(payload);
        return payload;
    }

    /** 结构校验：任何一项不合法都拒绝，保证加锁后的换入不会中途失败。 */
    private void validateStructure(SnapshotPayload payload) {
        if (payload.quotas() == null || payload.idempotency() == null || payload.auditEntries() == null) {
            throw new SnapshotException("corrupted snapshot: missing state sections");
        }
        for (SnapshotPayload.QuotaEntry q : payload.quotas()) {
            if (q.limit() < 0 || q.usedCount() < 0 || q.tenantId() == null || q.action() == null) {
                throw new SnapshotException("corrupted snapshot: invalid quota entry");
            }
        }
        for (SnapshotPayload.IdempotencyEntry e : payload.idempotency()) {
            if (e.tenantId() == null || e.idempotencyKey() == null || e.requestHash() == null || e.valueJson() == null) {
                throw new SnapshotException("corrupted snapshot: invalid idempotency entry");
            }
            // 预检幂等结果可反序列化，避免加锁换入时中途失败。
            try {
                MAPPER.readValue(e.valueJson(), Object.class);
            } catch (IOException ex) {
                throw new SnapshotException("corrupted snapshot: idempotency value not deserializable", ex);
            }
        }
        List<AuditEntry> entries = payload.auditEntries();
        long prev = 0;
        for (AuditEntry e : entries) {
            if (e.seq() <= prev) {
                throw new SnapshotException("corrupted snapshot: audit sequence not strictly increasing");
            }
            prev = e.seq();
        }
        if (payload.auditLastSeq() < prev) {
            throw new SnapshotException("corrupted snapshot: auditLastSeq smaller than max entry seq");
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> T parse(String json, Class<T> type, String what) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new SnapshotException("corrupted snapshot: cannot parse " + what, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
