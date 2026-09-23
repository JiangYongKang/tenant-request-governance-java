package com.github.highcumontoa.tenantrequestgovernancejava.web;

import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import com.github.highcumontoa.tenantrequestgovernancejava.web.dto.QuotaUpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地治理运维接口：查看审计流水、运行期调整配额。
 */
@RestController
public class AdminController {

    private final QuotaService quota;
    private final AuditService audit;

    public AdminController(QuotaService quota, AuditService audit) {
        this.quota = quota;
        this.audit = audit;
    }

    /** 审计流水（按序号单调递增返回）。 */
    @GetMapping("/api/admin/audit")
    public List<AuditEntry> audit() {
        return audit.all();
    }

    /** 运行期调整配额：立即生效，保留当前窗口与已有计数。 */
    @PostMapping("/api/admin/quota")
    public String updateQuota(@RequestBody QuotaUpdateRequest req) {
        quota.updateQuota(req.tenantId(), req.action(), req.limit());
        return "quota updated: tenant=" + req.tenantId() + ", action=" + req.action()
                + ", limit=" + req.limit() + " (existing window count preserved)";
    }
}
