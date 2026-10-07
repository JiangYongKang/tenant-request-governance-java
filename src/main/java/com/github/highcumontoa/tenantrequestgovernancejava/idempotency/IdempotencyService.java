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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 写操作幂等控制（内存）。
 * <p>键维度：租户 + 幂等键，保证不同租户互不干扰。
 *
 * <p><b>两阶段判定</b>（配合治理编排“先判幂等、后占配额”的顺序）：
 * <ol>
 *   <li>{@link #begin} 在 {@code compute} 原子块内“判占位 + 放占位”，返回三种结论：
 *     <ul>
 *       <li>{@link Leader}——本请求是该键的首次提交，占位已放入，配额尚未占用，
 *           由调用方继续走配额与业务，最终以 {@link #succeed}/{@link #fail} 收尾；</li>
 *       <li>{@link Waiter}——同键已有在先提交（处理中或已生效）：内容一致则
 *           {@link #join} 等待/读取首次结果（合流），全程不碰配额；内容不一致立即冲突。</li>
 *     </ul>
 *   </li>
 *   <li>leader 成功用 {@link #succeed} 发布首次结果；失败用 {@link #fail} 移除占位并
 *       异常完成，合流者据此解套，相同键允许重新发起首次提交。</li>
 * </ol>
 *
 * <p><b>并发语义</b>：同一键的并发重复提交在 {@code compute} 原子块内看到占位，
 * 转而等待首个请求的 {@link CompletableFuture}，因此业务副作用在任意并发度下只发生一次；
 * 合流发生在配额判定之前，所以即使窗口已满，重放也不会被限流挡住。
 *
 * <p><b>冲突语义</b>：同一幂等键携带不同请求内容（requestHash 不同）立即抛
 * {@link RejectReason#IDEMPOTENCY_CONFLICT}，无论在先提交处理中还是已生效，
 * 且该路径不消耗任何配额。
 */
public class IdempotencyService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Slot(String requestHash, CompletableFuture<Object> future) {
    }

    /**
     * {@link #begin} 的判定结论。leader 与 waiter 互斥，内容冲突直接以异常抛出。
     */
    public sealed interface Participation permits Leader, Waiter {
        String idempotencyKey();
    }

    /**
     * 首次提交：占位已就位，调用方接下来应先占配额、再执行业务，
     * 成功调 {@link #succeed}，任何失败调 {@link #fail}。
     */
    public record Leader(String compositeKey, String idempotencyKey) implements Participation {
    }

    /**
     * 重复提交：存在在先占位且 requestHash 一致，调用 {@link #join} 取首次结果；
     * 该路径不得占用配额，也不会再次产生副作用。
     */
    public record Waiter(String idempotencyKey) implements Participation {
    }

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    /**
     * 第一阶段：原子地“判占位 + 放占位”。
     * <ul>
     *   <li>键不存在 -> 放入“处理中”占位并返回 {@link Leader}；</li>
     *   <li>键已存在且 requestHash 一致 -> 返回 {@link Waiter}；</li>
     *   <li>键已存在但 requestHash 不一致 -> 立即抛 {@link RejectReason#IDEMPOTENCY_CONFLICT}。</li>
     * </ul>
     */
    public Participation begin(String tenantId, String idempotencyKey, String requestHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required for write operations");
        }
        String compositeKey = tenantId + "|" + idempotencyKey;
        CompletableFuture<Object> myFuture = new CompletableFuture<>();

        Participation[] decision = new Participation[1];
        slots.compute(compositeKey, (k, existing) -> {
            if (existing == null) {
                Slot created = new Slot(requestHash, myFuture);
                decision[0] = new Leader(compositeKey, idempotencyKey);
                return created;
            }
            if (!existing.requestHash().equals(requestHash)) {
                throw new GovernanceException(RejectReason.IDEMPOTENCY_CONFLICT,
                        "idempotency key '" + idempotencyKey
                                + "' was first submitted with a different request body");
            }
            decision[0] = new Waiter(idempotencyKey);
            return existing;
        });
        return decision[0];
    }

    /**
     * 第二阶段（waiter）：等待/读取首次提交的结果。重放不占用配额、不产生副作用。
     * 首次提交失败时，原样透传其运行时异常（如配额超限 / 业务失败，调用方据此拒绝本次重放）；
     * 若失败 leader 已撤位导致当前没有可合流的占位，返回空，由调用方重新 {@link #begin} 参选。
     */
    public <T> Optional<T> join(Waiter waiter, String tenantId) {
        String compositeKey = tenantId + "|" + waiter.idempotencyKey();
        Slot slot = slots.get(compositeKey);
        if (slot == null) {
            // 极小窗口：leader 刚失败并移除了占位。由调用方重新 begin 争当新 leader。
            return Optional.empty();
        }
        try {
            @SuppressWarnings("unchecked")
            T value = (T) slot.future().join();
            return Optional.of(value);
        } catch (java.util.concurrent.CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new GovernanceException(RejectReason.IDEMPOTENCY_CONFLICT,
                    "first attempt failed: " + (cause == null ? "unknown" : cause.getMessage()));
        }
    }

    /** 第二阶段（leader 成功）：发布首次结果，占位转为“已生效”。 */
    public void succeed(Leader leader, Object value) {
        Slot slot = slots.get(leader.compositeKey());
        if (slot != null) {
            slot.future().complete(value);
        }
    }

    /**
     * 第二阶段（leader 失败）：先移除自己的占位（移除后相同键可重新首次提交），
     * 再异常完成 future 解套合流者——它们醒来发现占位已不在，会走重新 begin 的路径。
     */
    public void fail(Leader leader, Throwable error) {
        Slot slot = slots.remove(leader.compositeKey());
        if (slot != null) {
            slot.future().completeExceptionally(error);
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
