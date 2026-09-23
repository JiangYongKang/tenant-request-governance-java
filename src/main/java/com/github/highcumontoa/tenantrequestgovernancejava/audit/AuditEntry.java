package com.github.highcumontoa.tenantrequestgovernancejava.audit;

/**
 * 一条审计记录。
 *
 * @param seq       单调递增序号
 * @param requestId 请求标识
 * @param tenantId  发起租户
 * @param action    操作
 * @param decision  判定结果（ACCEPT / REJECT / REPLAY）
 * @param reason    判定依据
 */
public record AuditEntry(long seq,
                         String requestId,
                         String tenantId,
                         String action,
                         String decision,
                         String reason,
                         long timestampMillis) {
}
