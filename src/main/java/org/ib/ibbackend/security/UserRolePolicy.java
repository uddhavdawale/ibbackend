package org.ib.ibbackend.security;

import java.util.Set;

public final class UserRolePolicy {
    public static final String DISABLED_ROLE = "disabled_user";

    private static final Set<String> STORE_ADMIN_ROLES = Set.of("store_admin");

    private static final Set<String> SYSTEM_ADMIN_ROLES = Set.of(
            "system_admin", "system_administrator", "super_admin", "org_admin"
    );

    private static final Set<String> ADMIN_USER_MANAGEMENT_ROLES = Set.of(
            "system_admin", "system_administrator", "owner", "admin", "shop_owner", "super_admin", "org_admin"
    );

    private static final Set<String> RETAILER_ROLES = Set.of(
            "retailer", "store_admin", "system_admin", "system_administrator", "owner", "admin", "shop_owner", "super_admin", "org_admin"
    );

    private UserRolePolicy() {
    }

    public static String normalize(String role) {
        if (role == null) {
            return "";
        }
        return role.trim().toLowerCase();
    }

    public static boolean isDisabled(UserLike userLike) {
        if (userLike == null) {
            return false;
        }
        return isDisabledRole(userLike.role());
    }

    public static boolean isDisabledRole(String role) {
        return DISABLED_ROLE.equals(normalize(role));
    }

    public static boolean isStoreAdmin(String role) {
        return STORE_ADMIN_ROLES.contains(normalize(role));
    }

    public static boolean isSystemAdmin(String role) {
        return SYSTEM_ADMIN_ROLES.contains(normalize(role));
    }

    public static boolean canManageUsers(String role) {
        return ADMIN_USER_MANAGEMENT_ROLES.contains(normalize(role));
    }

    public static boolean canApproveRetailers(String role) {
        return isSystemAdmin(role);
    }

    public static boolean canAccessPlatformAdmin(String role) {
        return isSystemAdmin(role);
    }

    public static boolean isRetailerRole(String role) {
        return RETAILER_ROLES.contains(normalize(role));
    }

    public interface UserLike {
        String role();
    }
}
