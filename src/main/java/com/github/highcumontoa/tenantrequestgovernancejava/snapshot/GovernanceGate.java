package com.github.highcumontoa.tenantrequestgovernancejava.snapshot;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 治理状态并发门：在线请求持读锁并行，快照恢复持写锁独占。
 * <p>导出状态收集在写锁内完成，得到的是某一时刻的全局一致状态；
 * 磁盘读写等慢操作不持锁。恢复在写锁内原子切换全部组件状态，
 * 因此对外要么整体恢复成功、要么完全看不到恢复。
 */
public class GovernanceGate {

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);

    /** 在线请求读锁（允许并发，不互斥）。 */
    public Lock readLock() {
        return rwLock.readLock();
    }

    /** 快照状态收集 / 原子切换写锁（独占，等待所有在途请求结束）。 */
    public Lock writeLock() {
        return rwLock.writeLock();
    }
}
