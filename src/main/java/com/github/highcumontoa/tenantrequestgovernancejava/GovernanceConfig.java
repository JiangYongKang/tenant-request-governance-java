package com.github.highcumontoa.tenantrequestgovernancejava;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.audit.AuditService;
import com.github.highcumontoa.tenantrequestgovernancejava.business.OrderService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
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

    @Bean
    public GovernanceFacade governanceFacade(AccessControlService accessControlService,
                                             QuotaService quotaService,
                                             IdempotencyService idempotencyService,
                                             AuditService auditService,
                                             OrderService orderService) {
        return new GovernanceFacade(accessControlService, quotaService, idempotencyService, auditService, orderService);
    }
}
