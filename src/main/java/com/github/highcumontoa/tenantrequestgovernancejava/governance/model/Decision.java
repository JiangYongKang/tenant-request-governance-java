package com.github.highcumontoa.tenantrequestgovernancejava.governance.model;

/**
 * 请求治理判定结果。每个被拒绝的原因都必须可区分。
 */
public enum Decision {
    /** 请求被接受并执行。 */
    ALLOWED,
    /** 幂等重放：返回首次结果，无副作用。 */
    IDEMPOTENT_REPLAY,
    /** 同一幂等键携带了不同的请求内容。 */
    IDEMPOTENCY_CONFLICT,
    /** 租户+操作维度的配额超限。 */
    QUOTA_EXCEEDED,
    /** 角色无权执行该操作。 */
    ROLE_FORBIDDEN,
    /** 跨租户访问被拒绝。 */
    TENANT_FORBIDDEN,
    /** 租户不存在。 */
    UNKNOWN_TENANT
}
