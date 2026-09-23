package com.github.highcumontoa.tenantrequestgovernancejava.governance.model;

/**
 * 治理判定与执行结果。
 *
 * @param requestId      请求标识
 * @param decision       判定结果
 * @param detail         可解释的判定依据
 * @param auditSeq       本次判定对应的审计序号
 * @param body           业务结果（幂等重放时为首次结果）
 * @param quotaRemaining 当前窗口剩余配额（未涉及配额时为 -1）
 */
public record GovernanceResult(
        String requestId,
        Decision decision,
        String detail,
        long auditSeq,
        String body,
        long quotaRemaining) {
}
