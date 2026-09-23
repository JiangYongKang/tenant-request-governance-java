package com.github.highcumontoa.tenantrequestgovernancejava.web.dto;

/**
 * 创建订单响应。
 */
public record OrderResponse(String orderId, boolean replayed, long auditSeq, String requestId) {
}
