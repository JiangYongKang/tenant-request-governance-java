package com.github.highcumontoa.tenantrequestgovernancejava.governance;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 写操作幂等提交（本地内存实现）。
 * <ul>
 *   <li>幂等键以租户为命名空间，不同租户互不影响；</li>
 *   <li>首个请求在条目锁内执行副作用并记录结果，副作用全进程最多执行一次；</li>
 *   <li>并发重复提交在同一把锁上等待，随后拿到首次结果（REPLAYED），不产生重复副作用；</li>
 *   <li>同一幂等键携带不同请求内容时，在锁内比较首次内容并明确拒绝（CONFLICT）。</li>
 * </ul>
 */
@Component
public class IdempotencyService {

    public enum Status { EXECUTED, REPLAYED, CONFLICT }

    public record Outcome(Status status, String body) {
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger effectCount = new AtomicInteger();

    public Outcome submit(String tenantId, String key, String payload, Supplier<String> effect) {
        String namespacedKey = tenantId + "\u0000" + key;
        String requestPayload = payload == null ? "" : payload;
        Entry entry = entries.computeIfAbsent(namespacedKey, k -> new Entry(requestPayload));

        // 条目级锁：不同幂等键之间互不阻塞；同键并发在这里串行化。
        synchronized (entry) {
            if (!entry.payload.equals(requestPayload)) {
                return new Outcome(Status.CONFLICT, null);
            }
            if (entry.completed) {
                return new Outcome(Status.REPLAYED, entry.body);
            }
            String body = effect.get();
            effectCount.incrementAndGet();
            entry.body = body;
            entry.completed = true;
            return new Outcome(Status.EXECUTED, body);
        }
    }

    /**
     * 副作用实际执行次数（测试断言用）。
     */
    public int effectCount() {
        return effectCount.get();
    }

    /** 单个幂等键的状态；锁即实例自身。 */
    private static final class Entry {
        private final String payload;
        private volatile String body;
        private volatile boolean completed;

        Entry(String payload) {
            this.payload = payload;
        }
    }
}
