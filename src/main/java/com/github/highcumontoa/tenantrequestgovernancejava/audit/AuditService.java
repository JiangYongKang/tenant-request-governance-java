package com.github.highcumontoa.tenantrequestgovernancejava.audit;

import com.github.highcumontoa.tenantrequestgovernancejava.common.SnapshotException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存审计：序号单调递增，记录只追加，不会被覆盖或错序。
 */
public class AuditService {

    /** 快照用：审计状态（当前序号 + 按序号排列的全部流水）。 */
    public record SnapshotState(long lastSeq, List<AuditEntry> entries) {
    }

    private final AtomicLong sequence = new AtomicLong(0);
    private volatile List<AuditEntry> entries = java.util.Collections.synchronizedList(new ArrayList<>());

    /**
     * 追加一条审计记录。
     *
     * @return 已落盘的审计条目（含单调递增序号）
     */
    public AuditEntry record(String requestId, String tenantId, String action,
                             String decision, String reason) {
        // 取号与追加在同一临界区内完成，保证列表顺序与序号严格一致（快照可直接取一致切片）。
        List<AuditEntry> target = entries;
        AuditEntry entry;
        synchronized (target) {
            long seq = sequence.incrementAndGet();
            entry = new AuditEntry(seq, requestId, tenantId, action, decision, reason,
                    System.currentTimeMillis());
            target.add(entry);
        }
        return entry;
    }

    /** 按序号顺序返回全部审计记录的快照。 */
    public List<AuditEntry> all() {
        List<AuditEntry> snapshot = entries;
        synchronized (snapshot) {
            return new ArrayList<>(snapshot);
        }
    }

    /** 当前序号（最后一条审计的序号；无记录时为 0）。 */
    public long currentSeq() {
        return sequence.get();
    }

    /**
     * 导出审计状态：当前序号 + 按序号排列的流水副本。
     * 调用方需持有治理读/写锁。
     */
    public SnapshotState exportState() {
        return new SnapshotState(sequence.get(), all());
    }

    /**
     * 用快照整体替换审计状态（原子换表）。
     * 必须先通过 {@link #validateSnapshot}；校验失败抛 {@link SnapshotException}，本服务状态不动。
     * 调用方需持有治理写锁：恢复后新记录严格从 lastSeq+1 继续编号，不重复、不回退。
     */
    public void restoreState(SnapshotState state) {
        validateSnapshot(state);
        List<AuditEntry> rebuilt = java.util.Collections.synchronizedList(new ArrayList<>(state.entries()));
        // 先发布新表再发布计数器的交叉风险由治理写锁排除（恢复期间无在途请求）。
        entries = rebuilt;
        sequence.set(state.lastSeq());
    }

    /**
     * 校验快照审计状态：序号必须从 1 起连续递增、与 lastSeq 一致、条目不能为空字段。
     * 任何不一致都拒绝恢复，防止恢复后出现重复序号、回退或错序。
     */
    public void validateSnapshot(SnapshotState state) {
        if (state == null) {
            throw new SnapshotException("snapshot audit section is missing");
        }
        List<AuditEntry> list = state.entries();
        if (list == null) {
            throw new SnapshotException("snapshot audit entries are missing");
        }
        long expected = 1L;
        for (AuditEntry e : list) {
            if (e == null) {
                throw new SnapshotException("snapshot audit contains null entry at seq " + expected);
            }
            if (e.seq() != expected) {
                throw new SnapshotException("snapshot audit sequence is not contiguous: expected seq "
                        + expected + " but got " + e.seq());
            }
            if (isBlank(e.requestId()) || isBlank(e.tenantId()) || isBlank(e.action())
                    || isBlank(e.decision()) || e.reason() == null) {
                throw new SnapshotException("snapshot audit entry at seq " + expected + " has missing fields");
            }
            expected++;
        }
        if (state.lastSeq() != list.size()) {
            throw new SnapshotException("snapshot audit lastSeq=" + state.lastSeq()
                    + " does not match entry count=" + list.size());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
