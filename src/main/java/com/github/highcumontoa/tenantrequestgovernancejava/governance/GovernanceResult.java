package com.github.highcumontoa.tenantrequestgovernancejava.governance;

/**
 * 治理后的业务结果。
 *
 * @param replayed 是否幂等重放
 * @param auditSeq 对应审计序号
 */
public record GovernanceResult<T>(T value, boolean replayed, long auditSeq) {
}
