package com.github.highcumontoa.tenantrequestgovernancejava.web.dto;

/**
 * 快照导出/恢复请求。path 缺省时使用服务端默认路径。
 */
public record SnapshotRequest(String path) {
}
