package com.github.highcumontoa.tenantrequestgovernancejava.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存审计：序号单调递增，记录只追加，不会被覆盖或错序。
 */
public class AuditService {

    private final AtomicLong sequence = new AtomicLong(0);
    private final List<AuditEntry> entries = java.util.Collections.synchronizedList(new ArrayList<>());

    /**
     * 追加一条审计记录。序号分配与列表追加在同一把锁内完成，
     * 保证列表物理顺序与序号顺序严格一致：不重复、不回退、不错序。
     *
     * @return 已落盘的审计条目（含单调递增序号）
     */
    public AuditEntry record(String requestId, String tenantId, String action,
                             String decision, String reason) {
        synchronized (entries) {
            long seq = sequence.incrementAndGet();
            AuditEntry entry = new AuditEntry(seq, requestId, tenantId, action, decision, reason,
                    System.currentTimeMillis());
            entries.add(entry);
            return entry;
        }
    }

    /** 按序号顺序返回全部审计记录的快照。 */
    public List<AuditEntry> all() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    /** 导出时刻已用到的最大序号（无记录时为 0）。 */
    public long lastSeq() {
        return sequence.get();
    }

    /**
     * 整体替换审计状态：恢复既有流水，并把序号发生器对齐到已恢复的最大序号，
     * 之后的新记录从该序号往后接着排——不重复、不回退、不错序。
     * 入参校验（序号连续性）由调用方在替换前完成；本方法要么全部换入、要么抛出不换。
     * 调用方需持有全局写锁。
     */
    public void importState(long lastSeq, List<AuditEntry> restored) {
        synchronized (entries) {
            entries.clear();
            entries.addAll(restored);
            sequence.set(lastSeq);
        }
    }
}
