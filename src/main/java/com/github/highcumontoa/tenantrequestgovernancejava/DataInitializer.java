package com.github.highcumontoa.tenantrequestgovernancejava;

import com.github.highcumontoa.tenantrequestgovernancejava.access.AccessControlService;
import com.github.highcumontoa.tenantrequestgovernancejava.governance.GovernanceFacade;
import com.github.highcumontoa.tenantrequestgovernancejava.quota.QuotaService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.Set;

/**
 * 演示数据初始化：注册两个租户并设置 CREATE_ORDER 窗口配额。
 * 两个租户独立配额，单个租户突发流量不影响另一个。
 * <p>顺序晚于快照启动恢复（{@link Order} 10），存在快照时以快照状态为准。
 */
@Configuration
public class DataInitializer {

    public static final String TENANT_A = "tenant-a";
    public static final String TENANT_B = "tenant-b";

    @Bean
    @Order(10)
    public ApplicationRunner initGovernanceData(AccessControlService accessControl, QuotaService quota) {
        return args -> {
            // 快照已恢复时这里仅补缺，不覆盖快照接回的租户授权与配额窗口。
            accessControl.registerTenantIfAbsent(TENANT_A,
                    Set.of(AccessControlService.ROLE_READER,
                            AccessControlService.ROLE_WRITER,
                            AccessControlService.ROLE_ADMIN));
            accessControl.registerTenantIfAbsent(TENANT_B,
                    Set.of(AccessControlService.ROLE_READER,
                            AccessControlService.ROLE_WRITER,
                            AccessControlService.ROLE_ADMIN));

            // tenant-a 窗口内仅允许 3 次创建（便于演示边界），tenant-b 允许 5 次。
            quota.initQuotaIfAbsent(TENANT_A, GovernanceFacade.ACTION_CREATE_ORDER, 3L);
            quota.initQuotaIfAbsent(TENANT_B, GovernanceFacade.ACTION_CREATE_ORDER, 5L);
        };
    }
}
