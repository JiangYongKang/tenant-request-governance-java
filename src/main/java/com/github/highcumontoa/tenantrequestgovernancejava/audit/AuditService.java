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
     * 追加一条审计记录。
     *
     * @return 已落盘的审计条目（含单调递增序号）
     */
    public AuditEntry record(String requestId, String tenantId, String action,
                             String decision, String reason) {
        long seq = sequence.incrementAndGet();
        AuditEntry entry = new AuditEntry(seq, requestId, tenantId, action, decision, reason,
                System.currentTimeMillis());
        entries.add(entry);
        return entry;
    }

    /** 按序号顺序返回全部审计记录的快照。 */
    public List<AuditEntry> all() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }
}
