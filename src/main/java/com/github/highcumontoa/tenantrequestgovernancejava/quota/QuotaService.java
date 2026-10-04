package com.github.highcumontoa.tenantrequestgovernancejava.quota;

import com.github.highcumontoa.tenantrequestgovernancejava.common.SnapshotException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /** 快照用：单个 (租户, 操作) 窗口的可序列化状态。 */
    public record WindowState(String tenantId, String action, long limit, long startMillis, long count) {
    }

    private record Key(String tenantId, String action) {
    }

    private static final class Window {
        private volatile long startMillis;
        private final AtomicLong count = new AtomicLong();
        private volatile long limit;

        Window(long startMillis, long limit) {
            this(startMillis, limit, 0L);
        }

        Window(long startMillis, long limit, long count) {
            this.startMillis = startMillis;
            this.limit = limit;
            this.count.set(count);
        }
    }

    private final long windowMillis;
    private final LongSupplier clock;
    private volatile ConcurrentHashMap<Key, Window> windows = new ConcurrentHashMap<>();

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
     * 仅当该 (租户, 操作) 从未配置过配额时才设置；已存在（含快照恢复而来）的窗口
     * 原样保留，不覆盖上限、不重置计数。用于“快照优先、演示数据补缺”的启动场景。
     */
    public void initQuotaIfAbsent(String tenantId, String action, long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("quota limit must be >= 0");
        }
        windows.computeIfAbsent(new Key(tenantId, action), k -> new Window(clock.getAsLong(), limit));
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

    /** 固定窗口长度（毫秒），快照恢复时需与快照一致。 */
    public long windowMillis() {
        return windowMillis;
    }

    /**
     * 导出所有已配置窗口（limit &gt;= 0）的一致状态。
     * 调用方需持有治理读/写锁；每个窗口在其自身锁内读取，计数与起点为同一临界区值。
     */
    public List<WindowState> exportState() {
        List<WindowState> out = new ArrayList<>(windows.size());
        for (Map.Entry<Key, Window> e : windows.entrySet()) {
            Window w = e.getValue();
            synchronized (w) {
                if (w.limit >= 0) {
                    out.add(new WindowState(e.getKey().tenantId(), e.getKey().action(),
                            w.limit, w.startMillis, w.count.get()));
                }
            }
        }
        return out;
    }

    /**
     * 用快照状态整体替换内存窗口表（原子换表）。
     * 调用方需持有治理写锁，且 {@code states} 已通过 {@link #validateWindowMillis} 校验。
     * 恢复时窗口已过期则把计数折叠为 0、起点归一到当前时刻，使接回后判定不劣化。
     */
    public void restoreState(List<WindowState> states) {
        long now = clock.getAsLong();
        ConcurrentHashMap<Key, Window> rebuilt = new ConcurrentHashMap<>();
        for (WindowState s : states) {
            boolean expired = now - s.startMillis() >= windowMillis;
            long startMillis = expired ? now : s.startMillis();
            long count = expired ? 0L : s.count();
            rebuilt.put(new Key(s.tenantId(), s.action()), new Window(startMillis, s.limit(), count));
        }
        windows = rebuilt;
    }

    /** 校验快照窗口参数与本实例一致，不一致明确拒绝（语义不兼容）。 */
    public void validateWindowMillis(long snapshotWindowMillis) {
        if (snapshotWindowMillis != windowMillis) {
            throw new SnapshotException("snapshot windowMillis=" + snapshotWindowMillis
                    + " is incompatible with running windowMillis=" + windowMillis);
        }
    }
}
