package com.github.highcumontoa.tenantrequestgovernancejava.idempotency;

import java.util.concurrent.CompletableFuture;

/**
 * 幂等占位判定（admit）的结果。判定本身不消耗任何限流配额：
 * <ul>
 *   <li>{@link Completed}：该键已有首次提交成功生效的结果，直接原样返回（重放）；</li>
 *   <li>{@link InFlight}：首次提交仍在进行中，本次为并发重复提交，合流等待首次结果；</li>
 *   <li>{@link Leader}：本次抢占到首位，只有它才需要继续占配额、执行业务，
 *       并在结束后恰好回调一次 succeeded/failed。</li>
 * </ul>
 * 同键不同内容由 admit 直接抛 IDEMPOTENCY_CONFLICT，不进入其中任何一种结果。
 */
public sealed interface Admission permits Admission.Completed, Admission.InFlight, Admission.Leader {

    /** 已有生效的首次结果（快照接回的结果同样以该形态呈现）。 */
    record Completed(Object value) implements Admission {
    }

    /** 首次提交进行中：future 完成后得到首次结果（或异常完成）。 */
    record InFlight(CompletableFuture<Object> future) implements Admission {
    }

    /** 抢占到首位：持有内部复合键用于业务结束后收尾。 */
    record Leader(String compositeKey) implements Admission {
    }
}
