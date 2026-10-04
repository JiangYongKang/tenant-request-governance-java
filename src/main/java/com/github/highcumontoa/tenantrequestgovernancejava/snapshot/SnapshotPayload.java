package com.github.highcumontoa.tenantrequestgovernancejava.snapshot;

import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;

import java.util.List;

/**
 * 治理状态快照载荷：一份快照完整承载限流、幂等、审计三类状态。
 *
 * @param quotas      各 (tenant, action) 维度的配额上限与窗口内已用计数
 * @param idempotency 已生效的幂等提交结果（仅含已完成的首次提交）
 * @param auditLastSeq 导出时刻审计已用到的最大序号（恢复后从此序号继续递增）
 * @param auditEntries 审计流水（按序号升序）
 */
public record SnapshotPayload(List<QuotaEntry> quotas,
                              List<IdempotencyEntry> idempotency,
                              long auditLastSeq,
                              List<AuditEntry> auditEntries) {

    /** 一个 (tenant, action) 维度的限流状态。 */
    public record QuotaEntry(String tenantId,
                             String action,
                             long limit,
                             long windowStartMillis,
                             long usedCount) {
    }

    /** 一条已生效的幂等提交结果。valueJson 为首次结果的 JSON 序列化形式。 */
    public record IdempotencyEntry(String tenantId,
                                   String idempotencyKey,
                                   String requestHash,
                                   String valueJson) {
    }
}
