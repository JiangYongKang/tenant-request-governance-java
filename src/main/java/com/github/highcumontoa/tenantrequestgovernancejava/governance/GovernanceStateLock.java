package com.github.highcumontoa.tenantrequestgovernancejava.governance;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * 治理状态全局读写锁。
 * <p>正常请求路径（限流/幂等/审计的读写）持有读锁，彼此并发不阻塞；
 * 快照导出与恢复持有写锁，从而获得“某一时刻的一致状态”与“整体生效或整体不生效”的语义。
 * 写锁只覆盖内存态的拷贝/替换，文件 IO 在锁外完成，避免长时间卡住在线请求。
 */
public class GovernanceStateLock {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    /** 在读锁下执行（在线请求路径，彼此并发）。 */
    public <T> T withReadLock(Supplier<T> action) {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 在写锁下执行（快照导出/恢复，独占内存态）。 */
    public <T> T withWriteLock(Supplier<T> action) {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
