package com.github.highcumontoa.tenantrequestgovernancejava.governance.model;

/**
 * 一次受治理的请求。
 *
 * @param requestId       请求标识（调用方生成，用于日志与审计关联）
 * @param tenantId        发起请求的租户
 * @param targetTenantId  请求作用的资源所属租户（跨租户访问时与 tenantId 不同）
 * @param role            调用方角色（ADMIN / WRITER / READER）
 * @param operation       操作名（如 order.create、order.read）
 * @param idempotencyKey  幂等键（写操作必填）
 * @param payload         请求内容
 */
public record GovernanceRequest(
        String requestId,
        String tenantId,
        String targetTenantId,
        String role,
        String operation,
        String idempotencyKey,
        String payload) {
}
