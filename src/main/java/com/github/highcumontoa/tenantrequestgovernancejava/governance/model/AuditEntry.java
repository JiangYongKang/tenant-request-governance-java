package com.github.highcumontoa.tenantrequestgovernancejava.governance.model;

import java.time.Instant;

/**
 * 一条审计记录。序号单调递增，写入后不可变。
 */
public record AuditEntry(
        long seq,
        Instant timestamp,
        String requestId,
        String tenantId,
        String role,
        String operation,
        String idempotencyKey,
        Decision decision,
        String detail) {
}
