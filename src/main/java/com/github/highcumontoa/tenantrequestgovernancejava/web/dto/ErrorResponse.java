package com.github.highcumontoa.tenantrequestgovernancejava.web.dto;

/**
 * 统一拒绝响应：可区分的原因 + 解释信息 + 审计序号 + 请求标识。
 */
public record ErrorResponse(String error, String reason, String message, Long auditSeq, String requestId) {
}
