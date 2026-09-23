package com.github.highcumontoa.tenantrequestgovernancejava.governance;

import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.GovernanceRequest;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.GovernanceResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 治理接口：提交请求、调整配额、查询审计。
 */
@RestController
@RequestMapping("/governance")
public class GovernanceController {

    private final GovernanceService governanceService;
    private final QuotaService quotaService;
    private final AuditLog auditLog;

    public GovernanceController(GovernanceService governanceService, QuotaService quotaService, AuditLog auditLog) {
        this.governanceService = governanceService;
        this.quotaService = quotaService;
        this.auditLog = auditLog;
    }

    @PostMapping("/requests")
    public GovernanceResult submit(@RequestBody GovernanceRequest request) {
        return governanceService.handle(request);
    }

    @PutMapping("/quotas/{tenantId}/{operation}")
    public Map<String, Object> setQuota(@PathVariable String tenantId, @PathVariable String operation,
                                        @RequestParam long limit) {
        quotaService.setQuota(tenantId, operation, limit);
        return Map.of("tenantId", tenantId, "operation", operation, "limit", limit);
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit() {
        return auditLog.entries();
    }
}
