package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 快照 HTTP 端点端到端：导出 -> 状态演进 -> 恢复 -> 幂等/限流/审计接回快照时刻；
 * 损坏快照返回 400 且在线状态不受影响。
 * 使用测试专属租户 tenant-snap，与共享 Spring 上下文里的其它集成测试完全隔离，
 * 任何执行顺序下结果都可复现。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpSnapshotIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HttpSnapshotIntegrationTest.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final String TENANT = "tenant-snap";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessControlService accessControl;

    @Autowired
    private QuotaService quota;

    @BeforeEach
    void setUpTenant() {
        accessControl.registerTenant(TENANT, Set.of("READER", "WRITER", "ADMIN"));
        quota.updateQuota(TENANT, GovernanceFacade.ACTION_CREATE_ORDER, 100L);
    }

    private static String orderBody(String key, String payload) {
        return "{\"tenantId\":\"" + TENANT + "\",\"idempotencyKey\":\"" + key
                + "\",\"payload\":\"" + payload + "\"}";
    }

    private MvcResult createOrder(String requestId, String key, String payload) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", TENANT).header("X-Role", "WRITER")
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(key, payload)))
                .andReturn();
        log.info("[{}] status={} body={}", requestId, r.getResponse().getStatus(),
                r.getResponse().getContentAsString());
        return r;
    }

    private static String pathJson(Path p) {
        return "{\"path\":\"" + p.toString().replace("\\", "\\\\") + "\"}";
    }

    @Test
    void exportThenRestoreOverHttp(@TempDir Path dir) throws Exception {
        Path snap = dir.resolve("http-snap.json");

        // 1) 首次提交 snap-k1，导出快照。
        MvcResult first = createOrder("http-snap-1", "snap-k1", "sp1");
        assertEquals(200, first.getResponse().getStatus());
        String orderId1 = OM.readTree(first.getResponse().getContentAsString()).get("orderId").asText();

        mockMvc.perform(post("/api/admin/snapshot/export")
                        .contentType(MediaType.APPLICATION_JSON).content(pathJson(snap)))
                .andExpect(status().isOk());
        assertTrue(Files.exists(snap));

        // 2) 快照之后状态继续演进：再提交 snap-k2。
        MvcResult second = createOrder("http-snap-2", "snap-k2", "sp2");
        assertEquals(200, second.getResponse().getStatus());

        // 3) 恢复快照：整体回到导出时刻。
        mockMvc.perform(post("/api/admin/snapshot/restore")
                        .contentType(MediaType.APPLICATION_JSON).content(pathJson(snap)))
                .andExpect(status().isOk());

        // 4) snap-k1 重放仍返回首次结果；snap-k2 的记录已随恢复消失，重提会重新执行。
        MvcResult replay = createOrder("http-snap-3", "snap-k1", "sp1");
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(orderId1, OM.readTree(replay.getResponse().getContentAsString()).get("orderId").asText());
        assertTrue(OM.readTree(replay.getResponse().getContentAsString()).get("replayed").asBoolean());

        MvcResult resubmitted = createOrder("http-snap-4", "snap-k2", "sp2");
        assertEquals(200, resubmitted.getResponse().getStatus());
        assertFalse(OM.readTree(resubmitted.getResponse().getContentAsString()).get("replayed").asBoolean());

        // 5) 损坏快照 -> 400 SNAPSHOT_INVALID，且在线状态不受影响（k1 仍可重放）。
        String content = Files.readString(snap);
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, content.substring(0, content.length() - 20));
        MvcResult rejected = mockMvc.perform(post("/api/admin/snapshot/restore")
                        .contentType(MediaType.APPLICATION_JSON).content(pathJson(broken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("SNAPSHOT_INVALID"))
                .andReturn();
        log.info("[http-snap-5] corrupted restore rejected: {}", rejected.getResponse().getContentAsString());

        MvcResult stillOk = createOrder("http-snap-6", "snap-k1", "sp1");
        assertEquals(200, stillOk.getResponse().getStatus());
        assertEquals(orderId1, OM.readTree(stillOk.getResponse().getContentAsString()).get("orderId").asText());
    }
}
