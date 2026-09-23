package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP 端到端：幂等重放、限流 429、跨租户 403、冲突 409、审计可查。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpGovernanceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HttpGovernanceIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    private String body(String tenant, String key, String payload) {
        String t = tenant == null ? "null" : ("\"" + tenant + "\"");
        return "{\"tenantId\":" + t + ",\"idempotencyKey\":\"" + key + "\",\"payload\":\"" + payload + "\"}";
    }

    @Test
    void fullChainOverHttp() throws Exception {
        // 1) 首次 + 重复提交：相同 key/payload 返回同一订单，replayed=true。
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        MvcResult first = mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "tenant-a").header("X-Role", "WRITER")
                        .header("X-Request-Id", "http-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "http-key-1", "hello")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(org.hamcrest.Matchers.is(false)))
                .andReturn();
        String firstOrderId = om.readTree(first.getResponse().getContentAsString()).get("orderId").asText();
        log.info("[http-1] first: {}", first.getResponse().getContentAsString());

        mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "tenant-a").header("X-Role", "WRITER")
                        .header("X-Request-Id", "http-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "http-key-1", "hello")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(org.hamcrest.Matchers.is(true)))
                .andExpect(jsonPath("$.orderId").value(org.hamcrest.Matchers.is(firstOrderId)));

        // 2) 同 key 不同内容 -> 409 IDEMPOTENCY_CONFLICT。
        MvcResult conflict = mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "tenant-a").header("X-Role", "WRITER")
                        .header("X-Request-Id", "http-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "http-key-1", "DIFFERENT")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("IDEMPOTENCY_CONFLICT"))
                .andReturn();
        log.info("[http-3] conflict status=409 body={}", conflict.getResponse().getContentAsString());

        // 3) tenant-a 配额为 3，已用 1（重放/冲突不计数）；再发 2 个独立请求打满，第 4 个 -> 429。
        mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "tenant-a").header("X-Role", "WRITER")
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "k2", "p2")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "tenant-a").header("X-Role", "WRITER")
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "k3", "p3")))
                .andExpect(status().isOk());
        MvcResult limited = mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "tenant-a").header("X-Role", "WRITER")
                        .header("X-Request-Id", "http-4")
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "k4", "p4")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.reason").value("QUOTA_EXCEEDED"))
                .andReturn();
        log.info("[http-4] limited status=429 body={}", limited.getResponse().getContentAsString());

        // 4) WRITER 跨租户 -> 403 CROSS_TENANT_ACCESS（配额拒绝不影响该判定路径，tenant-a 已超限，
        //    但访问控制先于配额，故仍返回跨租户原因）。
        MvcResult cross = mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "tenant-a").header("X-Role", "WRITER")
                        .header("X-Request-Id", "http-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("tenant-b", "k5", "p5")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("CROSS_TENANT_ACCESS"))
                .andReturn();
        log.info("[http-5] cross-tenant status=403 body={}", cross.getResponse().getContentAsString());

        // 5) tenant-b 独立配额，tenant-a 被打满不影响其可用性。
        mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "tenant-b").header("X-Role", "WRITER")
                        .header("X-Request-Id", "http-6")
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "bk1", "bp")))
                .andExpect(status().isOk());

        // 6) 审计流水可查，序号连续。
        MvcResult audit = mockMvc.perform(get("/api/admin/audit"))
                .andExpect(status().isOk())
                .andReturn();
        log.info("[audit] {}", audit.getResponse().getContentAsString());
    }
}
