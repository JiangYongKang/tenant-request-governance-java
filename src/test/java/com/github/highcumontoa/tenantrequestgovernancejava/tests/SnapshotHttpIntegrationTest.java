package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 快照运维接口的 HTTP 端到端：导出、恢复、坏快照被 400 拒绝且响应可解释。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SnapshotHttpIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotHttpIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportAndRestoreOverHttp(@TempDir Path dir) throws Exception {
        // 先制造一条治理状态，保证快照非空。
        mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "tenant-b").header("X-Role", "WRITER")
                        .header("X-Request-Id", "http-snap-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-b\",\"idempotencyKey\":\"http-snap-k1\",\"payload\":\"p\"}"))
                .andExpect(status().isOk());

        Path snap = dir.resolve("http.snap");
        String exportResp = mockMvc.perform(post("/api/admin/snapshots/export").param("path", snap.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("EXPORT"))
                .andExpect(jsonPath("$.formatVersion").value(1))
                .andExpect(jsonPath("$.tenants").value(2))
                .andReturn().getResponse().getContentAsString();
        log.info("[http-export] {}", exportResp);

        String restoreResp = mockMvc.perform(post("/api/admin/snapshots/restore").param("path", snap.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("RESTORE"))
                .andExpect(jsonPath("$.tenants").value(2))
                .andReturn().getResponse().getContentAsString();
        log.info("[http-restore] {}", restoreResp);

        // 损坏快照 -> 400 SNAPSHOT_REJECTED，服务状态不受影响。
        Path bad = dir.resolve("bad.snap");
        Files.writeString(bad, "GOVERNANCE-SNAPSHOT-V1\nsha256:deadbeef\n{\"formatVersion\":1}", StandardCharsets.UTF_8);
        String badResp = mockMvc.perform(post("/api/admin/snapshots/restore").param("path", bad.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SNAPSHOT_REJECTED"))
                .andExpect(jsonPath("$.reason").value("INVALID_SNAPSHOT"))
                .andReturn().getResponse().getContentAsString();
        log.info("[http-restore-bad] status=400 body={}", badResp);

        // 坏快照被拒后，正常接口仍然可用，幂等结果仍能重放。
        mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "tenant-b").header("X-Role", "WRITER")
                        .header("X-Request-Id", "http-snap-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-b\",\"idempotencyKey\":\"http-snap-k1\",\"payload\":\"p\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }
}
