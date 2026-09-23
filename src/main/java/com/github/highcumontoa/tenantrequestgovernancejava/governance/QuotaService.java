package com.github.highcumontoa.tenantrequestgovernancejava.governance;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按租户+操作维度的固定窗口配额限流。
 * <ul>
 *   <li>每个 (租户, 操作) 拥有独立的窗口对象与锁，单租户突发不会阻塞其它租户；</li>
 *   <li>窗口滚动与计数自增在同一把锁内完成，并发计数准确；</li>
 *   <li>配额上限与计数分离存储：运行期 {@link #setQuota} 只改上限，已有计数不丢失、不串用。</li>
 * </ul>
 */
@Component
public class QuotaService {

    /** 未显式配置时的默认配额。 */
    static final long DEFAULT_LIMIT = 1000L;

    private final long windowMillis;
    private final ConcurrentMap<String, Long> limits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public QuotaService() {
        this(60_000L);
    }

    /** 便于测试自定义窗口长度。 */
    QuotaService(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    private static String key(String tenantId, String operation) {
        return tenantId + "\u0000" + operation;
    }

    /**
     * 尝试为 (tenantId, operation) 占用一次配额。
     *
     * @return 占用后的剩余配额（&gt;=0 表示接受）；-1 表示超限被拒绝
     */
    public long tryAcquire(String tenantId, String operation) {
        String key = key(tenantId, operation);
        long limit = limits.getOrDefault(key, DEFAULT_LIMIT);
        Window window = windows.computeIfAbsent(key, k -> new Window());
        return window.acquire(limit, windowMillis, System.currentTimeMillis());
    }

    /**
     * 运行期调整配额，立即生效，不重置、不丢失已有计数。
     */
    public void setQuota(String tenantId, String operation, long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        limits.put(key(tenantId, operation), limit);
    }

    public long quotaOf(String tenantId, String operation) {
        return limits.getOrDefault(key(tenantId, operation), DEFAULT_LIMIT);
    }

    public long usedOf(String tenantId, String operation) {
        Window window = windows.get(key(tenantId, operation));
        return window == null ? 0L : window.snapshotUsed(windowMillis, System.currentTimeMillis());
    }

    /** 单个 (租户, 操作) 的固定窗口计数器。 */
    private static final class Window {
        private long windowStart;
        private long count;

        Window() {
            this.windowStart = System.currentTimeMillis();
        }

        synchronized long acquire(long limit, long windowMillis, long now) {
            if (now - windowStart >= windowMillis) {
                windowStart = now;
                count = 0;
            }
            if (count >= limit) {
                // 超限拒绝不增加计数，保证“已用计数”精确等于被接受的请求数
                return -1L;
            }
            count++;
            return limit - count;
        }

        synchronized long snapshotUsed(long windowMillis, long now) {
            if (now - windowStart >= windowMillis) {
                return 0L;
            }
            return count;
        }
    }
}
