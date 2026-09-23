package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 接线冒烟：正常提交、幂等重放、运行期调额后超限、审计查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GovernanceControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void submitIdempotentReplayAndQuotaAndAuditOverHttp() throws Exception {
        String body = """
                {"requestId":"http-1","tenantId":"tenant-a","targetTenantId":"tenant-a",
                 "role":"WRITER","operation":"order.http","idempotencyKey":"http-key","payload":"p1"}
                """;

        mockMvc.perform(post("/governance/requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOWED"))
                .andExpect(jsonPath("$.auditSeq").value(org.hamcrest.Matchers.greaterThan(0)));

        // 相同幂等键相同内容：重放
        mockMvc.perform(post("/governance/requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("IDEMPOTENT_REPLAY"));

        // 运行期收紧配额为 0，立即超限
        mockMvc.perform(put("/governance/quotas/tenant-a/order.http").param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(0));

        String otherKey = body.replace("http-1", "http-2").replace("http-key", "http-key-2");
        mockMvc.perform(post("/governance/requests").contentType(MediaType.APPLICATION_JSON).content(otherKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("QUOTA_EXCEEDED"));

        mockMvc.perform(get("/governance/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
    }
}
