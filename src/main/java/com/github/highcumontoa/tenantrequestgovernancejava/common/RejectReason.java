package com.github.highcumontoa.tenantrequestgovernancejava.common;

/**
 * 治理拒绝原因（可区分、可解释）。
 */
public enum RejectReason {
    UNKNOWN_TENANT,
    CROSS_TENANT_ACCESS,
    FORBIDDEN_ROLE,
    QUOTA_EXCEEDED,
    IDEMPOTENCY_CONFLICT
}
