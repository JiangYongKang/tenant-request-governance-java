package com.github.highcumontoa.tenantrequestgovernancejava.web.dto;

/**
 * 创建订单请求。
 *
 * @param tenantId       资源归属租户（不传则视为调用方租户；跨租户需 ADMIN）
 * @param idempotencyKey 幂等键（写操作必传）
 * @param payload        请求内容（相同幂等键必须携带相同内容）
 */
public record OrderRequest(String tenantId, String idempotencyKey, String payload) {
}
