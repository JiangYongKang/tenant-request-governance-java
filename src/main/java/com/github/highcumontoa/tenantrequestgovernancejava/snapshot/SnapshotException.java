package com.github.highcumontoa.tenantrequestgovernancejava.snapshot;

/**
 * 快照导出/恢复失败：版本不兼容、内容损坏、校验失败或 IO 失败。
 * 抛出该异常时，当前在用的治理状态保证不被污染或覆盖。
 */
public class SnapshotException extends RuntimeException {

    public SnapshotException(String message) {
        super(message);
    }

    public SnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
