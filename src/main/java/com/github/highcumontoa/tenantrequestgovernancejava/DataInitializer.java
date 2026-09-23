package com.github.highcumontoa.tenantrequestgovernancejava;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 演示数据初始化：注册两个租户并设置 CREATE_ORDER 窗口配额。
 * 两个租户独立配额，单个租户突发流量不影响另一个。
 */
@Configuration
public class DataInitializer {

    public static final String TENANT_A = "tenant-a";
    public static final String TENANT_B = "tenant-b";

    @Bean
    public ApplicationRunner initGovernanceData(AccessControlService accessControl, QuotaService quota) {
        return args -> {
            accessControl.registerTenant(TENANT_A,
                    Set.of(AccessControlService.ROLE_READER,
                            AccessControlService.ROLE_WRITER,
                            AccessControlService.ROLE_ADMIN));
            accessControl.registerTenant(TENANT_B,
                    Set.of(AccessControlService.ROLE_READER,
                            AccessControlService.ROLE_WRITER,
                            AccessControlService.ROLE_ADMIN));

            // tenant-a 窗口内仅允许 3 次创建（便于演示边界），tenant-b 允许 5 次。
            quota.updateQuota(TENANT_A, GovernanceFacade.ACTION_CREATE_ORDER, 3L);
            quota.updateQuota(TENANT_B, GovernanceFacade.ACTION_CREATE_ORDER, 5L);
        };
    }
}
