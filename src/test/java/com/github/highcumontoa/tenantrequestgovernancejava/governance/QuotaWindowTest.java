package com.github.highcumontoa.tenantrequestgovernancejava.governance;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 固定窗口滚动：窗口过期后计数归零，配额重新可用；租户/操作维度互不串用。
 */
class QuotaWindowTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaWindowTest.class);

    @Test
    void windowRolloverResetsCounter() throws InterruptedException {
        QuotaService quota = new QuotaService(50);
        quota.setQuota("tenant-a", "order.create", 2);

        assertEquals(1, quota.tryAcquire("tenant-a", "order.create"));
        assertEquals(0, quota.tryAcquire("tenant-a", "order.create"));
        assertEquals(-1, quota.tryAcquire("tenant-a", "order.create"), "窗口内必须超限");
        assertEquals(2, quota.usedOf("tenant-a", "order.create"));

        Thread.sleep(70);

        assertEquals(1, quota.tryAcquire("tenant-a", "order.create"), "新窗口配额应重新可用");
        assertEquals(1, quota.usedOf("tenant-a", "order.create"));
        log.info("窗口滚动后计数正确重置: used={}", quota.usedOf("tenant-a", "order.create"));

        // 其它租户、其它操作的计数互不串用
        assertEquals(0, quota.usedOf("tenant-b", "order.create"));
        assertEquals(0, quota.usedOf("tenant-a", "order.read"));
        log.info("跨租户/跨操作计数隔离正确");
    }
}
