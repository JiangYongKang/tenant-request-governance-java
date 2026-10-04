package com.github.highcumontoa.tenantrequestgovernancejava.snapshot;

import java.util.List;

/**
 * 快照文件承载的治理状态（可序列化 DTO）。
 * <p>包含：配额上限与窗口计数、已生效幂等结果、审计序号与流水、租户注册信息。
 */
public record SnapshotData(int formatVersion,
                           long takenAtMillis,
                           long windowMillis,
                           List<QuotaWindowState> quotas,
                           List<IdempotentRecord> idempotentResults,
                           AuditState audit,
                           List<TenantState> tenants) {

    /** 单个 (租户, 操作) 的固定窗口状态。 */
    public record QuotaWindowState(String tenantId,
                                   String action,
                                   long limit,
                                   long startMillis,
                                   long count) {
    }

    /** 一条已生效（COMPLETED）的幂等提交结果。 */
    public record IdempotentRecord(String tenantId,
                                   String idempotencyKey,
                                   String requestHash,
                                   Object value) {
    }

    /** 审计状态：当前序号与按序号排列的全部流水。 */
    public record AuditState(long lastSeq, List<AuditRecordState> entries) {
    }

    /** 审计流水条目（与 AuditEntry 对齐）。 */
    public record AuditRecordState(long seq,
                                   String requestId,
                                   String tenantId,
                                   String action,
                                   String decision,
                                   String reason,
                                   long timestampMillis) {
    }

    /** 租户注册信息及其授权角色集合。 */
    public record TenantState(String tenantId, List<String> roles) {
    }
}
