package com.github.highcumontoa.tenantrequestgovernancejava.web;

import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceStateLock;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import com.github.highcumontoa.tenantrequestgovernancejava.snapshot.SnapshotService;
import com.github.highcumontoa.tenantrequestgovernancejava.web.dto.QuotaUpdateRequest;
import com.github.highcumontoa.tenantrequestgovernancejava.web.dto.SnapshotRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

/**
 * 本地治理运维接口：查看审计流水、运行期调整配额、治理状态快照导出/恢复。
 */
@RestController
public class AdminController {

    /** 快照文件缺省路径（相对工作目录）。 */
    static final String DEFAULT_SNAPSHOT_PATH = "governance-snapshot.json";

    private final QuotaService quota;
    private final AuditService audit;
    private final SnapshotService snapshots;
    private final GovernanceStateLock stateLock;

    public AdminController(QuotaService quota, AuditService audit,
                           SnapshotService snapshots, GovernanceStateLock stateLock) {
        this.quota = quota;
        this.audit = audit;
        this.snapshots = snapshots;
        this.stateLock = stateLock;
    }

    /** 审计流水（按序号单调递增返回）。 */
    @GetMapping("/api/admin/audit")
    public List<AuditEntry> audit() {
        return audit.all();
    }

    /** 运行期调整配额：立即生效，保留当前窗口与已有计数。 */
    @PostMapping("/api/admin/quota")
    public String updateQuota(@RequestBody QuotaUpdateRequest req) {
        return stateLock.withReadLock(() -> {
            quota.updateQuota(req.tenantId(), req.action(), req.limit());
            return "quota updated: tenant=" + req.tenantId() + ", action=" + req.action()
                    + ", limit=" + req.limit() + " (existing window count preserved)";
        });
    }

    /**
     * 导出治理状态快照（限流窗口计数 + 幂等结果 + 审计流水）到本地文件。
     * 与在线请求并发安全：导出内容是全球写锁内某一时刻的一致状态。
     */
    @PostMapping("/api/admin/snapshot/export")
    public String exportSnapshot(@RequestBody(required = false) SnapshotRequest req) {
        Path path = pathOf(req);
        snapshots.exportTo(path);
        return "snapshot exported to " + path.toAbsolutePath()
                + " (formatVersion=" + SnapshotService.FORMAT_VERSION + ")";
    }

    /**
     * 从本地快照文件恢复治理状态：版本不符 / 内容损坏 / 校验失败一律拒绝（400），
     * 在线状态不被污染；校验通过则整体换入，审计序号从快照序号继续递增。
     */
    @PostMapping("/api/admin/snapshot/restore")
    public String restoreSnapshot(@RequestBody(required = false) SnapshotRequest req) {
        Path path = pathOf(req);
        snapshots.restoreFrom(path);
        return "snapshot restored from " + path.toAbsolutePath()
                + " (governance state swapped atomically)";
    }

    private static Path pathOf(SnapshotRequest req) {
        String p = req == null ? null : req.path();
        return Path.of(p == null || p.isBlank() ? DEFAULT_SNAPSHOT_PATH : p);
    }
}
