package org.ib.ibbackend.security;

import java.util.Set;

public final class UserRolePolicy {
    public static final String DISABLED_ROLE = "disabled_user";

    private static final Set<String> ADMIN_USER_MANAGEMENT_ROLES = Set.of(
            "store_admin", "owner", "admin", "shop_owner", "retailer", "super_admin", "org_admin"
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

    public static boolean canManageUsers(String role) {
        return ADMIN_USER_MANAGEMENT_ROLES.contains(normalize(role));
    }

    public interface UserLike {
        String role();
    }
}
