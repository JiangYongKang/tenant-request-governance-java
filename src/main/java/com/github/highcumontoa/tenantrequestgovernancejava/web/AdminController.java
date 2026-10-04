package com.github.highcumontoa.tenantrequestgovernancejava.web;

import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import com.github.highcumontoa.tenantrequestgovernancejava.snapshot.SnapshotManager;
import com.github.highcumontoa.tenantrequestgovernancejava.web.dto.QuotaUpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

/**
 * 本地治理运维接口：查看审计流水、运行期调整配额、快照导出 / 恢复。
 */
@RestController
public class AdminController {

    private final QuotaService quota;
    private final AuditService audit;
    private final SnapshotManager snapshots;

    public AdminController(QuotaService quota, AuditService audit, SnapshotManager snapshots) {
        this.quota = quota;
        this.audit = audit;
        this.snapshots = snapshots;
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

    /**
     * 导出治理快照到本地文件（默认 governance-snapshot.snap）。
     * 导出的是某一时刻的一致状态，与在线并发请求共处；临时文件 + 原子移动发布。
     */
    @PostMapping("/api/admin/snapshots/export")
    public SnapshotManager.SnapshotSummary exportSnapshot(
            @RequestParam(value = "path", required = false) String path) {
        return path == null || path.isBlank()
                ? snapshots.exportSnapshot()
                : snapshots.exportSnapshot(Path.of(path));
    }

    /**
     * 从本地快照整体恢复（可在运行期调用）。版本不符 / 损坏 / 校验失败返回 400，
     * 当前治理状态不受影响；成功则四组件状态原子切换，请求继续基于恢复后的状态判定。
     */
    @PostMapping("/api/admin/snapshots/restore")
    public SnapshotManager.SnapshotSummary restoreSnapshot(@RequestParam("path") String path) {
        return snapshots.restoreSnapshot(Path.of(path));
    }
}
