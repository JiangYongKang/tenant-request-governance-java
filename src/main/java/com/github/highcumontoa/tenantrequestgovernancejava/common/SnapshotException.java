package com.github.highcumontoa.tenantrequestgovernancejava.common;

/**
 * 快照导出 / 恢复失败时抛出：版本不兼容、内容损坏、写盘失败等。
 * <p>抛出该异常意味着治理状态未被改动，服务继续按恢复前的状态对外服务。
 */
public class SnapshotException extends RuntimeException {

    public SnapshotException(String message) {
        super(message);
    }

    public SnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
