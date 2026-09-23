package com.github.highcumontoa.tenantrequestgovernancejava.quota;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 按 租户 + 操作 维度的固定窗口配额限流（内存，并发安全）。
 * <p>隔离性：每个 (tenant, action) 拥有独立的窗口对象与锁，
 * 单个租户的突发流量只阻塞/影响其自身的 key，不波及其他租户。
 * <p>热更新：{@link #updateQuota} 只改上限，不重置窗口与计数。
 */
public class QuotaService {

    /** 一次配额占用的判定结果（limit/used 用于可解释输出）。 */
    public record AcquireOutcome(boolean allowed, long limit, long used) {
    }

    private record Key(String tenantId, String action) {
    }

    private static final class Window {
        private volatile long startMillis;
        private final AtomicLong count = new AtomicLong();
        private volatile long limit;

        Window(long startMillis, long limit) {
            this.startMillis = startMillis;
            this.limit = limit;
        }
    }

    private final long windowMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Key, Window> windows = new ConcurrentHashMap<>();

    public QuotaService(long windowMillis) {
        this(windowMillis, System::currentTimeMillis);
    }

    public QuotaService(long windowMillis, LongSupplier clock) {
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /** 设置/调整配额上限，立即生效；保留当前窗口与已有计数，不串用其它 key。 */
    public void updateQuota(String tenantId, String action, long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("quota limit must be >= 0");
        }
        windows.compute(new Key(tenantId, action), (k, existing) -> {
            if (existing == null) {
                return new Window(clock.getAsLong(), limit);
            }
            existing.limit = limit;
            return existing;
        });
    }

    /**
     * 尝试占用一个配额。未配置配额的 key 一律拒绝（limit=-1）。
     * 超限时回退本次计数，保证窗口内计数严格准确。
     */
    public AcquireOutcome tryAcquire(String tenantId, String action) {
        Key key = new Key(tenantId, action);
        Window window = windows.computeIfAbsent(key, k -> new Window(clock.getAsLong(), -1L));
        synchronized (window) {
            long now = clock.getAsLong();
            if (now - window.startMillis >= windowMillis) {
                window.startMillis = now;
                window.count.set(0L);
            }
            long limit = window.limit;
            if (limit < 0) {
                return new AcquireOutcome(false, -1L, window.count.get());
            }
            long used = window.count.incrementAndGet();
            if (used > limit) {
                window.count.decrementAndGet();
                return new AcquireOutcome(false, limit, limit);
            }
            return new AcquireOutcome(true, limit, used);
        }
    }

    /**
     * 回退一次已预占的配额（幂等重放/冲突等“未真正处理”场景使用）。
     * 仅当仍处于预占时所在的同一窗口内才回退，避免跨窗口误减。
     */
    public void release(String tenantId, String action) {
        Window window = windows.get(new Key(tenantId, action));
        if (window == null) {
            return;
        }
        synchronized (window) {
            if (clock.getAsLong() - window.startMillis < windowMillis && window.count.get() > 0) {
                window.count.decrementAndGet();
            }
        }
    }

    /** 当前窗口已用计数（测试/解释用）。 */
    public long currentCount(String tenantId, String action) {
        Window window = windows.get(new Key(tenantId, action));
        if (window == null) {
            return 0L;
        }
        synchronized (window) {
            if (clock.getAsLong() - window.startMillis >= windowMillis) {
                return 0L;
            }
            return window.count.get();
        }
    }

    /** 当前配额上限（解释用），未配置返回 -1。 */
    public long currentLimit(String tenantId, String action) {
        Window window = windows.get(new Key(tenantId, action));
        return window == null ? -1L : window.limit;
    }
}
