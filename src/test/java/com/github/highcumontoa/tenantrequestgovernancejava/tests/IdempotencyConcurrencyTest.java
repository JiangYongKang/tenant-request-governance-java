package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import com.github.highcumontoa.tenantrequestgovernancejava.governance.AccessPolicy;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.AuditLog;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.QuotaService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 并发重复提交：幂等键相同时副作用只发生一次，其余请求拿到首次结果。
 */
class IdempotencyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConcurrencyTest.class);

    private GovernanceService newService() {
        return new GovernanceService(new AccessPolicy(), new QuotaService(),
                new IdempotencyService(), new AuditLog());
    }

    private GovernanceRequest request(String id, String tenant, String idemKey, String payload) {
        return new GovernanceRequest(id, tenant, tenant, "WRITER", "order.create", idemKey, payload);
    }

    @Test
    void concurrentDuplicatesExecuteEffectOnceAndShareFirstResult() throws Exception {
        GovernanceService service = newService();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<GovernanceResult>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = "req-concurrent-" + i;
                tasks.add(() -> service.handle(request(requestId, "tenant-a", "idem-001", "payload-v1")));
            }
            List<Future<GovernanceResult>> futures = pool.invokeAll(tasks);
            int executed = 0;
            int replayed = 0;
            String firstBody = null;
            for (Future<GovernanceResult> f : futures) {
                GovernanceResult r = f.get(5, TimeUnit.SECONDS);
                log.info("requestId={} decision={} basis={} auditSeq={} body={}",
                        r.requestId(), r.decision(), r.detail(), r.auditSeq(), r.body());
                if (r.decision() == Decision.ALLOWED) {
                    executed++;
                    firstBody = r.body();
                } else {
                    assertSame(Decision.IDEMPOTENT_REPLAY, r.decision());
                    replayed++;
                }
                assertNotNull(r.body(), "重放也必须返回首次结果: " + r.requestId());
                if (firstBody != null) {
                    assertEquals(firstBody, r.body(), "所有并发请求必须返回同一首次结果");
                }
            }
            assertEquals(1, executed, "首次执行恰好一次");
            assertEquals(threads - 1, replayed, "其余全部为幂等重放");
            assertEquals(1, service.sideEffectCount(), "业务副作用必须只发生一次");
            log.info("== 结论: executed=1, replayed={}, sideEffectCount=1, 共享结果体={}", replayed, firstBody);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejectedAndHasNoEffect() {
        GovernanceService service = newService();
        GovernanceResult first = service.handle(request("req-1", "tenant-a", "idem-002", "payload-v1"));
        GovernanceResult conflict = service.handle(request("req-2", "tenant-a", "idem-002", "payload-v2"));

        log.info("requestId={} decision={} basis={} auditSeq={}",
                first.requestId(), first.decision(), first.detail(), first.auditSeq());
        log.info("requestId={} decision={} basis={} auditSeq={}",
                conflict.requestId(), conflict.decision(), conflict.detail(), conflict.auditSeq());

        assertSame(Decision.ALLOWED, first.decision());
        assertSame(Decision.IDEMPOTENCY_CONFLICT, conflict.decision());
        assertEquals(1, service.sideEffectCount(), "冲突请求不得产生副作用");
    }

    @Test
    void sameKeyInDifferentTenantsIsIndependent() {
        GovernanceService service = newService();
        GovernanceResult a = service.handle(request("req-a", "tenant-a", "shared-key", "payload"));
        GovernanceResult b = service.handle(request("req-b", "tenant-b", "shared-key", "payload"));

        log.info("requestId={} decision={} basis={} auditSeq={}",
                a.requestId(), a.decision(), a.detail(), a.auditSeq());
        log.info("requestId={} decision={} basis={} auditSeq={}",
                b.requestId(), b.decision(), b.detail(), b.auditSeq());

        assertSame(Decision.ALLOWED, a.decision());
        assertSame(Decision.ALLOWED, b.decision());
        assertEquals(2, service.sideEffectCount(), "不同租户的同键互不影响");
    }
}
