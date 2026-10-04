package com.github.highcumontoa.tenantrequestgovernancejava.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 可选的启动期快照恢复：通过配置 {@code governance.snapshot.restore-on-start=路径} 开启。
 * <p>顺序早于 {@link com.github.highcumontoa.tenantrequestgovernancejava.DataInitializer}
 * （{@link Order} 更小），因此接回的租户/配额会覆盖演示初始化；
 * 文件不存在或校验失败时快速失败，明确拒绝“带病启动”。
 */
@Component
@Order(0)
@ConditionalOnProperty(name = "governance.snapshot.restore-on-start")
public class SnapshotRestoreRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRestoreRunner.class);

    private final SnapshotManager snapshots;
    private final String path;

    public SnapshotRestoreRunner(SnapshotManager snapshots,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${governance.snapshot.restore-on-start}") String path) {
        this.snapshots = snapshots;
        this.path = path;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path source = Path.of(path);
        if (!Files.exists(source)) {
            throw new IllegalStateException("governance.snapshot.restore-on-start points to a missing file: " + source);
        }
        SnapshotManager.SnapshotSummary summary = snapshots.restoreSnapshot(source);
        log.info("governance snapshot restored on startup from {}: quotas={}, idempotent={}, audit={}, tenants={}",
                summary.path(), summary.quotaWindows(), summary.idempotentResults(),
                summary.auditEntries(), summary.tenants());
    }
}
