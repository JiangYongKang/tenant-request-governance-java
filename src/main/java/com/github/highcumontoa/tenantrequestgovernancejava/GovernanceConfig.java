package com.github.highcumontoa.tenantrequestgovernancejava;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.business.OrderService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import com.github.highcumontoa.tenantrequestgovernancejava.snapshot.GovernanceGate;
import com.github.highcumontoa.tenantrequestgovernancejava.snapshot.SnapshotManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 治理组件装配（内存实现）。
 */
@Configuration
public class GovernanceConfig {

    @Bean
    public AccessControlService accessControlService() {
        return new AccessControlService();
    }

    @Bean
    public AuditService auditService() {
        return new AuditService();
    }

    @Bean
    public QuotaService quotaService() {
        return new QuotaService(60_000L);
    }

    @Bean
    public IdempotencyService idempotencyService() {
        return new IdempotencyService();
    }

    @Bean
    public OrderService orderService() {
        return new OrderService();
    }

    /** 在线请求（读锁并行）与快照恢复/状态收集（写锁独占）之间的并发门。 */
    @Bean
    public GovernanceGate governanceGate() {
        return new GovernanceGate();
    }

    @Bean
    public SnapshotManager snapshotManager(GovernanceGate governanceGate,
                                           AccessControlService accessControlService,
                                           QuotaService quotaService,
                                           IdempotencyService idempotencyService,
                                           AuditService auditService) {
        return new SnapshotManager(governanceGate, accessControlService, quotaService,
                idempotencyService, auditService);
    }

    @Bean
    public GovernanceFacade governanceFacade(AccessControlService accessControlService,
                                             QuotaService quotaService,
                                             IdempotencyService idempotencyService,
                                             AuditService auditService,
                                             OrderService orderService,
                                             GovernanceGate governanceGate) {
        return new GovernanceFacade(accessControlService, quotaService, idempotencyService,
                auditService, orderService, governanceGate);
    }
}
