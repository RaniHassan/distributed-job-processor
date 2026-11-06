package com.example.jobprocessor.security.authorization;

import com.example.jobprocessor.user.domain.Role;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Server-side role-to-permission mapping. Tokens carry only the role, so changing this mapping
 * takes effect on the next request without reissuing tokens.
 */
public final class RolePermissions {

    private static final Map<Role, Set<Permission>> PERMISSIONS = new EnumMap<>(Role.class);

    static {
        PERMISSIONS.put(Role.USER, EnumSet.of(Permission.JOB_CREATE, Permission.JOB_READ, Permission.JOB_CANCEL));
        // ADMIN: the same job permissions, no ownership bypass, plus operational metrics (approved decision D2).
        PERMISSIONS.put(Role.ADMIN, EnumSet.of(Permission.JOB_CREATE, Permission.JOB_READ, Permission.JOB_CANCEL,
                Permission.METRICS_READ));
    }

    private RolePermissions() {
    }

    public static Set<Permission> of(Role role) {
        return Set.copyOf(PERMISSIONS.get(role));
    }
}
