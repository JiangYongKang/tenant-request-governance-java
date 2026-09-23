package com.github.highcumontoa.tenantrequestgovernancejava.governance;

import com.github.highcumontoa.tenantrequestgovernancejava.governance.model.Decision;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户与角色范围内的访问控制。
 * <ul>
 *   <li>未知租户 → {@link Decision#UNKNOWN_TENANT}；</li>
 *   <li>访问他人租户资源（targetTenantId != tenantId）→ {@link Decision#TENANT_FORBIDDEN}，仅 ADMIN 放行；</li>
 *   <li>角色无权执行该操作 → {@link Decision#ROLE_FORBIDDEN}：READER 只读，WRITER 可写，ADMIN 全部。</li>
 * </ul>
 */
@Component
public class AccessPolicy {

    private final Set<String> tenants = ConcurrentHashMap.newKeySet();
    private static final Set<String> KNOWN_ROLES = Set.of("ADMIN", "WRITER", "READER");

    public AccessPolicy() {
        registerTenant("tenant-a");
        registerTenant("tenant-b");
        registerTenant("tenant-c");
    }

    public Decision check(String tenantId, String targetTenantId, String role, String operation) {
        if (!tenants.contains(tenantId)) {
            return Decision.UNKNOWN_TENANT;
        }
        if (targetTenantId != null && !targetTenantId.equals(tenantId) && !"ADMIN".equals(role)) {
            return Decision.TENANT_FORBIDDEN;
        }
        if (role == null || !KNOWN_ROLES.contains(role)) {
            return Decision.ROLE_FORBIDDEN;
        }
        boolean writeOperation = operation == null || !(operation.endsWith(".read") || operation.endsWith(".query"));
        if (writeOperation && "READER".equals(role)) {
            return Decision.ROLE_FORBIDDEN;
        }
        return null;
    }

    public void registerTenant(String tenantId) {
        tenants.add(tenantId);
    }
}
