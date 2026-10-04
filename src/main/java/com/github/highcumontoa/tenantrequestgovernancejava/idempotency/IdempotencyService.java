package com.github.highcumontoa.tenantrequestgovernancejava.idempotency;

import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 写操作幂等控制（内存）。
 * <p>键维度：租户 + 幂等键，保证不同租户互不干扰。
 * <p>并发语义：首个请求放入“处理中”占位并执行业务；同一键的并发重复提交
 * 在 {@code compute} 原子块内看到占位，转而等待首个请求的结果（合流），
 * 因此业务副作用在任意并发度下只发生一次。
 * <p>冲突语义：同一幂等键携带不同请求内容（requestHash 不同）立即抛
 * {@link RejectReason#IDEMPOTENCY_CONFLICT}，明确拒绝。
 */
public class IdempotencyService {

    private record Slot(String requestHash, CompletableFuture<Object> future) {
    }

    /** 快照用：一条已生效幂等结果的可序列化状态。 */
    public record CompletedState(String tenantId, String idempotencyKey, String requestHash, Object value) {
    }

    /** 复合键分隔符，与 {@link #execute} 中保持一致。 */
    private static final String KEY_SEPARATOR = "|";

    private volatile ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    /** 只读查询结论：无记录（需首次执行）/ 可重放 / 内容冲突 / 首个请求仍在处理中（按首次合流）。 */
    public sealed interface Lookup<T> {
        /** 没有已生效记录：调用方应继续占配额并走首次执行。 */
        record NotFound<T>() implements Lookup<T> {
        }

        /** 命中已生效结果：直接重放，不占配额、不再执行副作用。 */
        record Replay<T>(T value) implements Lookup<T> {
        }

        /** 同键不同内容：明确冲突拒绝。 */
        record Conflict<T>() implements Lookup<T> {
        }
    }

    /**
     * 只读查询幂等键，不放入占位、不触发业务。
     * <p>用途：治理链路在占配额之前先判断是否为“已完成提交的重放”，
     * 使窗口打满后重放仍然成功且不消耗业务配额（含快照恢复后的重放）。
     * 首个请求仍在处理中时返回 {@link Lookup.NotFound}，由后续 {@code execute} 合流等待。
     */
    public <T> Lookup<T> lookup(String tenantId, String idempotencyKey, String requestHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required for write operations");
        }
        Slot slot = slots.get(tenantId + KEY_SEPARATOR + idempotencyKey);
        if (slot == null) {
            return new Lookup.NotFound<>();
        }
        if (!slot.requestHash().equals(requestHash)) {
            return new Lookup.Conflict<>();
        }
        CompletableFuture<Object> future = slot.future();
        if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
            return new Lookup.NotFound<>();
        }
        @SuppressWarnings("unchecked")
        T value = (T) future.getNow(null);
        return new Lookup.Replay<>(value);
    }

    @SuppressWarnings("unchecked")
    public <T> IdempotentResult<T> execute(String tenantId, String idempotencyKey,
                                           String requestHash, Callable<T> producer) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required for write operations");
        }
        String compositeKey = tenantId + KEY_SEPARATOR + idempotencyKey;
        CompletableFuture<Object> myFuture = new CompletableFuture<>();

        // compute 对单个 key 加锁，保证“判占位 + 放入占位”原子完成。
        Slot[] decided = new Slot[1];
        boolean[] first = new boolean[1];
        slots.compute(compositeKey, (k, existing) -> {
            if (existing == null) {
                first[0] = true;
                Slot created = new Slot(requestHash, myFuture);
                decided[0] = created;
                return created;
            }
            decided[0] = existing;
            return existing;
        });

        if (first[0]) {
            try {
                T value = producer.call();
                myFuture.complete(value);
                return new IdempotentResult<>(value, false);
            } catch (RuntimeException | Error ex) {
                myFuture.completeExceptionally(ex);
                slots.remove(compositeKey, decided[0]);
                throw ex;
            } catch (Exception ex) {
                myFuture.completeExceptionally(ex);
                slots.remove(compositeKey, decided[0]);
                throw new GovernanceException(RejectReason.IDEMPOTENCY_CONFLICT,
                        "business execution failed: " + ex.getMessage());
            }
        }

        // 重复提交：先比对内容，再决定拒绝或返回首次结果。
        Slot existing = decided[0];
        if (!existing.requestHash().equals(requestHash)) {
            throw new GovernanceException(RejectReason.IDEMPOTENCY_CONFLICT,
                    "idempotency key '" + idempotencyKey + "' was first submitted with a different request body");
        }
        try {
            return new IdempotentResult<>((T) existing.future().join(), true);
        } catch (java.util.concurrent.CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new GovernanceException(RejectReason.IDEMPOTENCY_CONFLICT,
                    "first attempt failed: " + (cause == null ? "unknown" : cause.getMessage()));
        }
    }

    /**
     * 导出全部“已生效”幂等结果。处理中（future 未完成）的占位不导出，
     * 因为它不代表一次已完成提交，恢复后允许重新提交。
     * 调用方需持有治理读/写锁，保证与其它组件取自同一时刻。
     */
    public List<CompletedState> exportState() {
        List<CompletedState> out = new ArrayList<>(slots.size());
        for (var entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            CompletableFuture<Object> future = slot.future();
            if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
                continue;
            }
            String compositeKey = entry.getKey();
            int sep = compositeKey.indexOf(KEY_SEPARATOR);
            if (sep <= 0 || sep == compositeKey.length() - 1) {
                continue; // 防御：非本服务格式的键，不导出
            }
            String tenantId = compositeKey.substring(0, sep);
            String idempotencyKey = compositeKey.substring(sep + 1);
            out.add(new CompletedState(tenantId, idempotencyKey, slot.requestHash(), future.getNow(null)));
        }
        return out;
    }

    /**
     * 用快照整体替换幂等表（原子换表）。调用方需持有治理写锁，且入参已完成非空校验。
     * 接回后同一租户同一幂等键的重复提交命中已完成 future，返回首次结果、不再产生副作用。
     */
    public void restoreState(List<CompletedState> states) {
        ConcurrentHashMap<String, Slot> rebuilt = new ConcurrentHashMap<>();
        for (CompletedState s : states) {
            String compositeKey = s.tenantId() + KEY_SEPARATOR + s.idempotencyKey();
            CompletableFuture<Object> future = new CompletableFuture<>();
            future.complete(s.value());
            rebuilt.put(compositeKey, new Slot(s.requestHash(), future));
        }
        slots = rebuilt;
    }
}
