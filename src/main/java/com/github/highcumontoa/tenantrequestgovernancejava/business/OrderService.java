package com.github.highcumontoa.tenantrequestgovernancejava.business;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟订单写业务。{@link #sideEffectCount} 暴露真实副作用发生次数，
 * 用于验证幂等：任何重复提交（含并发）都不得使该计数累加。
 */
public class OrderService {

    private final AtomicLong sideEffects = new AtomicLong();

    /**
     * 创建订单。每次调用都产生一次真实副作用（计数 +1）。
     *
     * @param tenantId 资源归属租户
     * @param payload  订单内容
     * @return 订单号
     */
    public String createOrder(String tenantId, String payload) {
        sideEffects.incrementAndGet();
        return "ORD-" + tenantId + "-" + UUID.randomUUID();
    }

    /** 真实副作用发生总次数。 */
    public long sideEffectCount() {
        return sideEffects.get();
    }
}
