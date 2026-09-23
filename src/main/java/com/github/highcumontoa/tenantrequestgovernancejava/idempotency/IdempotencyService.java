package com.github.highcumontoa.tenantrequestgovernancejava.idempotency;

import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason;

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

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> IdempotentResult<T> execute(String tenantId, String idempotencyKey,
                                           String requestHash, Callable<T> producer) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required for write operations");
        }
        String compositeKey = tenantId + "|" + idempotencyKey;
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
}
