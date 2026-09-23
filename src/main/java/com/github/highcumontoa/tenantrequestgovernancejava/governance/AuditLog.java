package com.github.highcumontoa.tenantrequestgovernancejava.governance;

import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地内存审计日志：
 * <ul>
 *   <li>序号由 {@link AtomicLong} 单调递增分配，不会回退、重复或错序；</li>
 *   <li>记录只追加，不提供修改或删除入口；</li>
 *   <li>{@link CopyOnWriteArrayList} 保证并发追加下读快照一致。</li>
 * </ul>
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final AtomicLong nextSeq = new AtomicLong(1);
    private final CopyOnWriteArrayList<AuditEntry> entries = new CopyOnWriteArrayList<>();

    /**
     * 追加一条审计记录并返回（含分配的序号）。
     * <p>序号分配与列表追加在同一把锁内完成，保证插入顺序与序号顺序严格一致：
     * 任何后续操作都不会让记录错序、重复或被覆盖。
     */
    public synchronized AuditEntry append(String requestId, String tenantId, String role, String operation,
                             String idempotencyKey, Decision decision, String detail) {
        long seq = nextSeq.getAndIncrement();
        AuditEntry entry = new AuditEntry(seq, Instant.now(), requestId, tenantId, role, operation,
                idempotencyKey, decision, detail);
        entries.add(entry);
        log.info("[audit#{}] requestId={} tenant={} role={} op={} idemKey={} decision={} detail={}",
                seq, requestId, tenantId, role, operation, idempotencyKey, decision, detail);
        return entry;
    }

    /**
     * 按序号升序返回全部审计记录（快照视图）。
     */
    public List<AuditEntry> entries() {
        return List.copyOf(entries);
    }
}
