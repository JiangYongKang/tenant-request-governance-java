package com.github.highcumontoa.tenantrequestgovernancejava.web.dto;

/**
 * 运行期配额调整请求（立即生效，不清空窗口计数）。
 */
public record QuotaUpdateRequest(String tenantId, String action, long limit) {
}
