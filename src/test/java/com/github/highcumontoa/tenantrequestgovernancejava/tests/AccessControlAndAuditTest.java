package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import com.github.highcumontoa.tenantrequestgovernancejava.governance.AccessPolicy;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.AuditLog;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.QuotaService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.Decision;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.GovernanceRequest;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.GovernanceResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 越权访问拒绝原因可区分；审计序号在并发下单调递增、不重复、不错序。
 */
class AccessControlAndAuditTest {

    private static final Logger log = LoggerFactory.getLogger(AccessControlAndAuditTest.class);

    private GovernanceService newService(AuditLog auditLog) {
        return new GovernanceService(new AccessPolicy(), new QuotaService(),
                new IdempotencyService(), auditLog);
    }

    private GovernanceResult call(GovernanceService s, String id, String tenant, String target,
                                  String role, String op) {
        GovernanceResult r = s.handle(new GovernanceRequest(id, tenant, target, role, op, "key-" + id, "p"));
        log.info("requestId={} decision={} basis={} auditSeq={}",
                r.requestId(), r.decision(), r.detail(), r.auditSeq());
        return r;
    }

    @Test
    void rejectionReasonsAreDistinguishable() {
        GovernanceService service = newService(new AuditLog());

        GovernanceResult crossTenant = call(service, "req-cross", "tenant-a", "tenant-b", "WRITER", "order.create");
        assertSame(Decision.TENANT_FORBIDDEN, crossTenant.decision());

        GovernanceResult readerWrite = call(service, "req-reader", "tenant-a", "tenant-a", "READER", "order.create");
        assertSame(Decision.ROLE_FORBIDDEN, readerWrite.decision());

        GovernanceResult unknown = call(service, "req-unknown", "tenant-x", "tenant-x", "WRITER", "order.create");
        assertSame(Decision.UNKNOWN_TENANT, unknown.decision());

        GovernanceResult readerRead = call(service, "req-read", "tenant-a", "tenant-a", "READER", "order.read");
        assertSame(Decision.ALLOWED, readerRead.decision(), "READER 读操作应放行");

        // ADMIN 可跨租户；WRITER 正常写
        GovernanceService service2 = newService(new AuditLog());
        GovernanceResult admin = call(service2, "req-admin", "tenant-a", "tenant-b", "ADMIN", "order.create");
        assertSame(Decision.ALLOWED, admin.decision(), "ADMIN 跨租户应放行");
        GovernanceResult writer = call(service2, "req-writer", "tenant-a", "tenant-a", "WRITER", "order.create");
        assertSame(Decision.ALLOWED, writer.decision());
    }

    @Test
    void auditSeqsAreMonotonicUnderConcurrencyAndEveryDecisionIsRecorded() throws Exception {
        AuditLog auditLog = new AuditLog();
        GovernanceService service = newService(auditLog);
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<GovernanceResult>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String id = "audit-" + i;
                // 混合：一半本租户写，一半跨租户拒绝，确保两种判定都留痕
                final String target = (i % 2 == 0) ? "tenant-a" : "tenant-b";
                final String idemKey = "idem-audit-" + i;
                tasks.add(() -> service.handle(
                        new GovernanceRequest(id, "tenant-a", target, "WRITER", "order.create",
                                idemKey, "p")));
            }
            for (Future<GovernanceResult> f : pool.invokeAll(tasks)) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<AuditEntry> entries = auditLog.entries();
        assertEquals(threads, entries.size(), "每个判定（接受或拒绝）都必须有审计记录");
        long expected = 1;
        for (AuditEntry e : entries) {
            log.info("audit#{} requestId={} tenant={} op={} decision={} detail={}",
                    e.seq(), e.requestId(), e.tenantId(), e.operation(), e.decision(), e.detail());
            assertEquals(expected, e.seq(), "审计序号必须从 1 开始连续递增、不错序");
            expected++;
        }
        assertEquals(threads + 1, expected);
        log.info("== 结论: {} 条审计记录序号 1..{} 连续单调递增", entries.size(), threads);
    }
}
