package com.github.highcumontoa.tenantrequestgovernancejava.common;

/**
 * 单次请求的调用方上下文。
 */
public record RequestContext(String requestId, String tenantId, String role, String resourceTenantId) {
}
