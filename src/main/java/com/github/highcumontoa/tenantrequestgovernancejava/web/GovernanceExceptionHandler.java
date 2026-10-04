package com.github.highcumontoa.tenantrequestgovernancejava.web;

import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason;
import com.github.highcumontoa.tenantrequestgovernancejava.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将治理异常映射为可区分的 HTTP 响应。
 * 拒绝同样会留痕（facade 内），这里回查该请求最近一条审计序号以便追溯。
 */
@RestControllerAdvice
public class GovernanceExceptionHandler {

    private static final Map<RejectReason, HttpStatus> STATUS = Map.of(
            RejectReason.UNKNOWN_TENANT, HttpStatus.FORBIDDEN,
            RejectReason.CROSS_TENANT_ACCESS, HttpStatus.FORBIDDEN,
            RejectReason.FORBIDDEN_ROLE, HttpStatus.FORBIDDEN,
            RejectReason.QUOTA_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS,
            RejectReason.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT
    );

    private final AuditService audit;

    public GovernanceExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<ErrorResponse> handleGovernance(GovernanceException ex,
                                                          jakarta.servlet.http.HttpServletRequest request) {
        String requestId = (String) request.getAttribute("governance.requestId");
        Long auditSeq = findAuditSeq(requestId);
        ErrorResponse body = new ErrorResponse(
                "GOVERNANCE_REJECTED",
                ex.getReason().name(),
                ex.getMessage(),
                auditSeq,
                requestId);
        return ResponseEntity.status(STATUS.getOrDefault(ex.getReason(), HttpStatus.INTERNAL_SERVER_ERROR)).body(body);
    }

    /** 快照版本不兼容 / 内容损坏 / 校验失败：400，在线治理状态不受影响。 */
    @ExceptionHandler(com.github.highcumontoa.tenantrequestgovernancejava.snapshot.SnapshotException.class)
    public ResponseEntity<ErrorResponse> handleSnapshot(
            com.github.highcumontoa.tenantrequestgovernancejava.snapshot.SnapshotException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        String requestId = (String) request.getAttribute("governance.requestId");
        ErrorResponse body = new ErrorResponse(
                "SNAPSHOT_REJECTED",
                "SNAPSHOT_INVALID",
                ex.getMessage(),
                null,
                requestId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private Long findAuditSeq(String requestId) {
        if (requestId == null) {
            return null;
        }
        return audit.all().stream()
                .filter(e -> requestId.equals(e.requestId()))
                .map(AuditEntry::seq)
                .reduce((first, second) -> second)
                .orElse(null);
    }
}
