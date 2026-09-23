package com.github.highcumontoa.tenantrequestgovernancejava.tests;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditEntry;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.business.OrderService;
import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RequestContext;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceResult;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 访问控制测试：未知租户、READER 越权写、WRITER 跨租户、ADMIN 跨租户放行。
 * 越权必须在配额/业务之前被拒绝（副作用为 0），且原因可区分、审计可追溯。
 */
class AccessControlTest {

    private static final Logger log = LoggerFactory.getLogger(AccessControlTest.class);

    private AccessControlService access;
    private AuditService audit;
    private OrderService orders;
    private GovernanceFacade facade;

    @BeforeEach
    void setUp() {
        access = new AccessControlService();
        access.registerTenant("tenant-a", Set.of("READER", "WRITER", "ADMIN"));
        access.registerTenant("tenant-b", Set.of("READER", "WRITER", "ADMIN"));
        QuotaService quota = new QuotaService(60_000L, () -> 1_000L);
        quota.updateQuota("tenant-a", GovernanceFacade.ACTION_CREATE_ORDER, 3L);
        quota.updateQuota("tenant-b", GovernanceFacade.ACTION_CREATE_ORDER, 3L);
        audit = new AuditService();
        orders = new OrderService();
        facade = new GovernanceFacade(access, quota, new IdempotencyService(), audit, orders);
    }

    private RequestContext ctx(String requestId, String tenant, String role, String resourceTenant) {
        return new RequestContext(requestId, tenant, role, resourceTenant);
    }

    private String run(String rid, String tenant, String role, String resourceTenant) {
        GovernanceException ex = assertThrows(GovernanceException.class,
                () -> facade.createOrder(ctx(rid, tenant, role, resourceTenant), UUID.randomUUID().toString(), "p"));
        AuditEntry e = audit.all().stream().filter(a -> rid.equals(a.requestId())).reduce((x, y) -> y).orElseThrow();
        log.info("[{}] rejected reason={} decision={} auditSeq={} basis={}",
                rid, ex.getReason(), e.decision(), e.seq(), e.reason());
        return ex.getReason().name();
    }

    @Test
    void unknownTenantRejected() {
        assertEquals("UNKNOWN_TENANT", run("req-ac-unknown", "tenant-x", "WRITER", "tenant-x"));
        assertEquals(0L, orders.sideEffectCount(), "越权请求不得产生副作用");
    }

    @Test
    void readerCannotWrite() {
        assertEquals("FORBIDDEN_ROLE", run("req-ac-reader", "tenant-a", "READER", "tenant-a"));
        assertEquals(0L, orders.sideEffectCount());
    }

    @Test
    void writerCannotCrossTenant() {
        assertEquals("CROSS_TENANT_ACCESS", run("req-ac-cross", "tenant-a", "WRITER", "tenant-b"));
        assertEquals(0L, orders.sideEffectCount());
    }

    @Test
    void ungrantedRoleRejected() {
        // tenant-a 虽注册，但伪造角色 ADMIN2 不属其授权集合。
        assertEquals("FORBIDDEN_ROLE", run("req-ac-role", "tenant-a", "ADMIN2", "tenant-a"));
    }

    @Test
    void adminCanWriteAcrossTenantsAndAuditAccepted() {
        String rid = "req-ac-admin";
        GovernanceResult<String> r = facade.createOrder(
                ctx(rid, "tenant-a", "ADMIN", "tenant-b"), UUID.randomUUID().toString(), "p");
        AuditEntry e = audit.all().stream().filter(a -> rid.equals(a.requestId())).findFirst().orElseThrow();
        log.info("[{}] cross-tenant admin accepted orderId={} auditSeq={} decision={}",
                rid, r.value(), r.auditSeq(), e.decision());
        assertFalse(r.replayed());
        assertEquals("ACCEPT", e.decision());
        assertEquals(1L, orders.sideEffectCount());
    }

    @Test
    void rejectReasonsAreDistinguishable() {
        String r1 = run("req-dist-1", "tenant-x", "WRITER", "tenant-x");
        String r2 = run("req-dist-2", "tenant-a", "READER", "tenant-a");
        String r3 = run("req-dist-3", "tenant-a", "WRITER", "tenant-b");
        assertNotEquals(r1, r2);
        assertNotEquals(r2, r3);
        assertNotEquals(r1, r3);
        log.info("[distinguishable] {} / {} / {}", r1, r2, r3);
    }
}
