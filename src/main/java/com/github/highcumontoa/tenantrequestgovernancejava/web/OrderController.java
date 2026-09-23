package com.github.highcumontoa.tenantrequestgovernancejava.web;

import com.github.highcumontoa.tenantrequestgovernancejava.common.RequestContext;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceResult;
import com.github.highcumontoa.tenantrequestgovernancejava.web.dto.OrderRequest;
import com.github.highcumontoa.tenantrequestgovernancejava.web.dto.OrderResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 订单写接口（演示治理链路）。
 * <p>请求头：X-Tenant-Id（调用方租户）、X-Role（READER/WRITER/ADMIN）、X-Request-Id（可选，缺省自动生成）。
 * 请求体：tenantId（资源归属租户，缺省=调用方租户；跨租户仅 ADMIN）、idempotencyKey、payload。
 */
@RestController
public class OrderController {

    private final GovernanceFacade facade;

    public OrderController(GovernanceFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/api/orders")
    public ResponseEntity<OrderResponse> create(@RequestHeader("X-Tenant-Id") String tenantId,
                                                @RequestHeader("X-Role") String role,
                                                @RequestHeader(value = "X-Request-Id", required = false) String requestIdHeader,
                                                @RequestBody OrderRequest request,
                                                HttpServletRequest httpRequest) {
        String requestId = (requestIdHeader == null || requestIdHeader.isBlank())
                ? "req-" + UUID.randomUUID()
                : requestIdHeader;
        httpRequest.setAttribute("governance.requestId", requestId);

        String resourceTenant = (request.tenantId() == null || request.tenantId().isBlank())
                ? tenantId
                : request.tenantId();
        RequestContext ctx = new RequestContext(requestId, tenantId, role, resourceTenant);

        GovernanceResult<String> result = facade.createOrder(ctx, request.idempotencyKey(), request.payload());
        return ResponseEntity.ok(new OrderResponse(result.value(), result.replayed(), result.auditSeq(), requestId));
    }
}
