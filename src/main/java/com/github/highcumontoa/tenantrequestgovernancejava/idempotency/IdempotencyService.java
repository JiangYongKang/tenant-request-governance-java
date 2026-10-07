package com.github.highcumontoa.tenantrequestgovernancejava.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason;
import com.github.highcumontoa.tenantrequestgovernancejava.snapshot.SnapshotPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Slot(String requestHash, CompletableFuture<Object> future) {
    }

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    /**
     * 原子地完成“判占位 + 放占位”，<b>不触碰限流配额</b>。
     * 因此窗口打满后，重放仍能拿到 {@link Admission.Completed} / {@link Admission.InFlight}，
     * 不会被配额挡住；只有抢到 {@link Admission.Leader} 的首个请求才继续去占配额。
     * <p>同键不同内容（requestHash 不一致）立即抛
     * {@link RejectReason#IDEMPOTENCY_CONFLICT}，无论该键是已完成还是处理中。
     */
    public Admission admit(String tenantId, String idempotencyKey, String requestHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required for write operations");
        }
        String compositeKey = tenantId + "|" + idempotencyKey;
        CompletableFuture<Object> myFuture = new CompletableFuture<>();

        // compute 对单个 key 加锁，保证“判占位 + 放入占位”原子完成。
        Admission[] decision = new Admission[1];
        slots.compute(compositeKey, (k, existing) -> {
            if (existing == null) {
                Slot created = new Slot(requestHash, myFuture);
                decision[0] = new Admission.Leader(compositeKey);
                return created;
            }
            if (!existing.requestHash().equals(requestHash)) {
                throw new GovernanceException(RejectReason.IDEMPOTENCY_CONFLICT,
                        "idempotency key '" + idempotencyKey
                                + "' was first submitted with a different request body");
            }
            if (existing.future().isDone() && !existing.future().isCompletedExceptionally()
                    && !existing.future().isCancelled()) {
                decision[0] = new Admission.Completed(existing.future().join());
            } else {
                decision[0] = new Admission.InFlight(existing.future());
            }
            return existing;
        });
        return decision[0];
    }

    /** Leader 业务成功：固化首次结果，唤醒所有合流等待的重放。 */
    public void succeeded(Admission.Leader leader, Object value) {
        slots.computeIfPresent(leader.compositeKey(), (k, slot) -> {
            slot.future().complete(value);
            return slot;
        });
    }

    /** Leader 业务失败：异常完成占位并移除，唤醒等待者，允许后续同键重试。 */
    public void failed(Admission.Leader leader, Throwable error) {
        slots.computeIfPresent(leader.compositeKey(), (k, slot) -> {
            slot.future().completeExceptionally(error);
            return null;
        });
    }

    /**
     * 重放方等待并取出首次结果（{@link Admission.Completed} 的已完成结果，
     * 或 {@link Admission.InFlight} 合流等待）。首次执行失败时原样透传其失败原因。
     */
    public static Object awaitResult(CompletableFuture<Object> future) {
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new GovernanceException(RejectReason.IDEMPOTENCY_CONFLICT,
                    "first attempt failed: " + (cause == null ? "unknown" : cause.getMessage()));
        }
    }

    /**
     * 导出全部已生效（首次执行已成功完成）的幂等结果。
     * 处理中的占位不导出——导出发生在全局写锁内，正常请求路径不会处于处理中状态。
     */
    public List<SnapshotPayload.IdempotencyEntry> exportState() {
        List<SnapshotPayload.IdempotencyEntry> out = new ArrayList<>();
        slots.forEach((compositeKey, slot) -> {
            CompletableFuture<Object> future = slot.future();
            if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
                return;
            }
            int sep = compositeKey.indexOf('|');
            String tenantId = compositeKey.substring(0, sep);
            String idempotencyKey = compositeKey.substring(sep + 1);
            try {
                out.add(new SnapshotPayload.IdempotencyEntry(tenantId, idempotencyKey,
                        slot.requestHash(), MAPPER.writeValueAsString(future.join())));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("idempotent result is not JSON-serializable: " + compositeKey, e);
            }
        });
        return out;
    }

    /**
     * 整体替换幂等状态：恢复的结果以“已完成”的占位放回，
     * 之后同租户同键的重复提交直接合流到首次结果，业务副作用不会再次发生。
     * 调用方需持有全局写锁（恢复要么整体生效、要么不生效）。
     */
    public void importState(List<SnapshotPayload.IdempotencyEntry> entries) {
        Map<String, Slot> restored = new HashMap<>();
        for (SnapshotPayload.IdempotencyEntry e : entries) {
            Object value;
            try {
                value = MAPPER.readValue(e.valueJson(), Object.class);
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("corrupted idempotency value for key " + e.idempotencyKey(), ex);
            }
            CompletableFuture<Object> future = new CompletableFuture<>();
            future.complete(value);
            restored.put(e.tenantId() + "|" + e.idempotencyKey(), new Slot(e.requestHash(), future));
        }
        slots.clear();
        slots.putAll(restored);
    }
}
