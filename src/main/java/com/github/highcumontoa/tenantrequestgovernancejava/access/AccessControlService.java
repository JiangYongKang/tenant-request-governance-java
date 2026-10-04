package com.github.highcumontoa.tenantrequestgovernancejava.access;

import com.github.highcumontoa.tenantrequestgovernancejava.common.GovernanceException;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RejectReason;
import com.github.highcumontoa.tenantrequestgovernancejava.common.RequestContext;
import com.github.highcumontoa.tenantrequestgovernancejava.common.SnapshotException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户注册 + 角色访问控制（内存）。
 * <p>判定顺序与原因：
 * <ol>
 *   <li>调用方租户未注册 -> UNKNOWN_TENANT</li>
 *   <li>角色不属于该租户 / 未知角色 -> FORBIDDEN_ROLE</li>
 *   <li>访问其它租户资源且非 ADMIN -> CROSS_TENANT_ACCESS</li>
 *   <li>同租户下角色无权执行该操作（READER 不可写）-> FORBIDDEN_ROLE</li>
 * </ol>
 */
public class AccessControlService {

    /** 内置角色：READER 只读；WRITER 可读写（限本租户）；ADMIN 可读写且可跨租户。 */
    public static final String ROLE_READER = "READER";
    public static final String ROLE_WRITER = "WRITER";
    public static final String ROLE_ADMIN = "ADMIN";

    private static final Set<String> KNOWN_ROLES = Set.of(ROLE_READER, ROLE_WRITER, ROLE_ADMIN);

    private volatile Map<String, Set<String>> tenantRoles = new ConcurrentHashMap<>();

    public void registerTenant(String tenantId, Set<String> roles) {
        for (String role : roles) {
            if (!KNOWN_ROLES.contains(role)) {
                throw new IllegalArgumentException("unknown role: " + role);
            }
        }
        tenantRoles.put(tenantId, Set.copyOf(roles));
    }

    /** 仅在租户尚未注册时注册；已注册（含快照恢复而来）的租户授权集合原样保留。 */
    public void registerTenantIfAbsent(String tenantId, Set<String> roles) {
        for (String role : roles) {
            if (!KNOWN_ROLES.contains(role)) {
                throw new IllegalArgumentException("unknown role: " + role);
            }
        }
        tenantRoles.putIfAbsent(tenantId, Set.copyOf(roles));
    }

    public boolean isKnownTenant(String tenantId) {
        return tenantRoles.containsKey(tenantId);
    }

    /** 校验访问控制；不通过抛 {@link GovernanceException}，原因可区分。 */
    public void check(RequestContext ctx, String action) {
        Set<String> roles = tenantRoles.get(ctx.tenantId());
        if (roles == null) {
            throw new GovernanceException(RejectReason.UNKNOWN_TENANT,
                    ctx.requestId() + " tenant not registered: " + ctx.tenantId());
        }
        if (ctx.role() == null || !roles.contains(ctx.role())) {
            throw new GovernanceException(RejectReason.FORBIDDEN_ROLE,
                    ctx.requestId() + " role '" + ctx.role() + "' is not granted to tenant " + ctx.tenantId());
        }
        String resourceTenant = ctx.resourceTenantId() == null ? ctx.tenantId() : ctx.resourceTenantId();
        boolean crossTenant = !resourceTenant.equals(ctx.tenantId());
        if (crossTenant && !ROLE_ADMIN.equals(ctx.role())) {
            throw new GovernanceException(RejectReason.CROSS_TENANT_ACCESS,
                    ctx.requestId() + " tenant " + ctx.tenantId() + " may not access resource of tenant "
                            + resourceTenant + " (requires ADMIN)");
        }
        if (isWriteAction(action) && ROLE_READER.equals(ctx.role())) {
            throw new GovernanceException(RejectReason.FORBIDDEN_ROLE,
                    ctx.requestId() + " role READER may not perform write action " + action);
        }
    }

    private boolean isWriteAction(String action) {
        return action.startsWith("CREATE") || action.startsWith("UPDATE") || action.startsWith("DELETE");
    }

    /** 快照用：租户注册信息（租户 -> 授权角色集合）。 */
    public record TenantState(String tenantId, Set<String> roles) {
    }

    /** 导出全部租户注册信息。调用方需持有治理读/写锁。 */
    public List<TenantState> exportState() {
        List<TenantState> out = new ArrayList<>(tenantRoles.size());
        tenantRoles.forEach((tenantId, roles) -> out.add(new TenantState(tenantId, Set.copyOf(roles))));
        return out;
    }

    /**
     * 用快照整体替换租户注册表（原子换表）。未知角色 / 空字段拒绝整次恢复。
     * 调用方需持有治理写锁。
     */
    public void restoreState(List<TenantState> states) {
        Map<String, Set<String>> rebuilt = new ConcurrentHashMap<>();
        for (TenantState s : states) {
            if (s.tenantId() == null || s.tenantId().isBlank()) {
                throw new SnapshotException("snapshot tenant entry has blank tenantId");
            }
            if (s.roles() == null || s.roles().isEmpty()) {
                throw new SnapshotException("snapshot tenant '" + s.tenantId() + "' has no roles");
            }
            for (String role : s.roles()) {
                if (!KNOWN_ROLES.contains(role)) {
                    throw new SnapshotException("snapshot tenant '" + s.tenantId() + "' carries unknown role: " + role);
                }
            }
            rebuilt.put(s.tenantId(), Set.copyOf(s.roles()));
        }
        tenantRoles = rebuilt;
    }
}
