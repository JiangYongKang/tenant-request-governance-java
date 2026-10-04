package com.github.highcumontoa.tenantrequestgovernancejava;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.business.OrderService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceStateLock;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import com.github.highcumontoa.tenantrequestgovernancejava.snapshot.SnapshotService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 治理组件装配（内存实现 + 本地快照持久化）。
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

    @Bean
    public GovernanceStateLock governanceStateLock() {
        return new GovernanceStateLock();
    }

    @Bean
    public GovernanceFacade governanceFacade(AccessControlService accessControlService,
                                             QuotaService quotaService,
                                             IdempotencyService idempotencyService,
                                             AuditService auditService,
                                             OrderService orderService,
                                             GovernanceStateLock governanceStateLock) {
        return new GovernanceFacade(accessControlService, quotaService, idempotencyService,
                auditService, orderService, governanceStateLock);
    }

    @Bean
    public SnapshotService snapshotService(QuotaService quotaService,
                                           IdempotencyService idempotencyService,
                                           AuditService auditService,
                                           GovernanceStateLock governanceStateLock) {
        return new SnapshotService(quotaService, idempotencyService, auditService, governanceStateLock);
    }
}
