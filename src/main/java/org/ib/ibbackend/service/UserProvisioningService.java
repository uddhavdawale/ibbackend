package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.SignupRequest;
import org.ib.ibbackend.entity.RetailerStatus;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.Set;

@Service
public class UserProvisioningService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9._-]{3,50}$");

    private static final Set<String> ADMIN_CAPABLE_ROLES = new HashSet<>(Arrays.asList(
            "store_admin", "system_admin", "system_administrator", "owner", "admin", "shop_owner", "retailer", "super_admin", "org_admin"
    ));

    private static final Set<String> CANONICAL_ROLES = new HashSet<>(List.of(
            "store_admin", "system_admin", "system_administrator", "store_manager", "store_billing_viewer", "store_inventory_viewer", "billing_manager", "inventory_manager", "super_admin", "org_admin"
    ));

    private final TenantRepository tenantRepository;
    private final PasswordSecurityService passwordSecurityService;

    public UserProvisioningService(TenantRepository tenantRepository,
                                   PasswordSecurityService passwordSecurityService) {
        this.tenantRepository = tenantRepository;
        this.passwordSecurityService = passwordSecurityService;
    }

    public String requireNormalizedEmail(SignupRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        return request.getEmail().trim().toLowerCase(Locale.ROOT);
    }

    public String requireNormalizedUsername(SignupRequest request) {
        if (request == null || request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }

        String normalizedUsername = request.getUsername().trim().toLowerCase(Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(normalizedUsername).matches()) {
            throw new IllegalArgumentException("Username must be 3-50 characters and contain only letters, numbers, dot, underscore, or hyphen");
        }

        return normalizedUsername;
    }

    public User provisionUser(SignupRequest request,
                              String normalizedEmail,
                              String normalizedUsername,
                              Optional<User> creatorOpt) {
        return creatorOpt
                .map(creator -> buildChildUserFromCreator(creator, request, normalizedEmail, normalizedUsername))
                .orElseGet(() -> buildRootUser(request, normalizedEmail, normalizedUsername));
    }

    private User buildChildUserFromCreator(User creator,
                                           SignupRequest request,
                                           String normalizedEmail,
                                           String normalizedUsername) {
        if (creator == null) {
            throw new IllegalArgumentException("Creator is required");
        }

        String creatorRole = normalizeRole(creator.getRole());
        if (!ADMIN_CAPABLE_ROLES.contains(creatorRole)) {
            throw new SecurityException("Only admin roles can create users");
        }

        if (creator.getTenant() == null) {
            throw new SecurityException("Creator tenant is required");
        }

        String targetRole = normalizeRole(
                request.getRole() == null || request.getRole().isBlank() ? "store_manager" : request.getRole());
        if (!UserRolePolicy.isSystemAdmin(creatorRole) && UserRolePolicy.isSystemAdmin(targetRole)) {
            throw new SecurityException("Only system admins can create system admin users");
        }

        User user = buildCommonUserFields(request, normalizedEmail, normalizedUsername);

        String businessNameFallback = resolveBusinessName(request.getBusinessName());
        String creatorBusinessName = creator.getBusinessName() == null || creator.getBusinessName().isBlank()
                ? businessNameFallback
                : creator.getBusinessName().trim();

        String creatorOwnerName = creator.getOwnerName() == null || creator.getOwnerName().isBlank()
                ? "Owner"
                : creator.getOwnerName().trim();

        user.setBusinessName(creatorBusinessName);
        user.setOwnerName(creatorOwnerName);
        user.setTenant(creator.getTenant());
        user.setRole(targetRole);
        return user;
    }

    private User buildRootUser(SignupRequest request, String normalizedEmail, String normalizedUsername) {
        User user = buildCommonUserFields(request, normalizedEmail, normalizedUsername);
        String businessName = resolveBusinessName(request.getBusinessName());

        user.setBusinessName(businessName);
        user.setOwnerName(request.getOwnerName() == null || request.getOwnerName().isBlank() ? "Owner" : request.getOwnerName().trim());
        user.setRole(normalizeRole(request.getRole() == null || request.getRole().isBlank() ? "store_admin" : request.getRole()));
        user.setTenant(createUniqueTenantForUser(businessName, normalizedEmail, request));
        return user;
    }

    private User buildCommonUserFields(SignupRequest request,
                                       String normalizedEmail,
                                       String normalizedUsername) {
        User user = new User();
        user.setEmail(normalizedEmail);
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordSecurityService.hashNewPassword(request.getPasswordHash()));
        user.setPhone(trimToNull(request.getPhone()));
        user.setGstin(trimToNull(request.getGstin()));
        user.setPan(trimToNull(request.getPan()));
        user.setAddress(trimToNull(request.getAddress()));
        user.setCity(trimToNull(request.getCity()));
        user.setPincode(trimToNull(request.getPincode()));
        user.setApprovalStatus(RetailerStatus.PENDING_APPROVAL);
        user.setDisabled(true);
        return user;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String resolveBusinessName(String businessName) {
        String safeBusinessName = businessName == null ? "Default Business" : businessName.trim();
        if (safeBusinessName.isBlank()) {
            safeBusinessName = "Default Business";
        }
        return safeBusinessName;
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "store_manager";
        }

        String normalized = role.trim().toLowerCase();
        String canonical = switch (normalized) {
            case "retailer", "shop_owner", "owner", "admin" -> "store_admin";
            case "system_admin", "system_administrator" -> "system_admin";
            case "store_billing_viwer" -> "store_billing_viewer";
            case "store_inventory_viwer" -> "store_inventory_viewer";
            case "store_admin", "store_manager", "store_billing_viewer", "store_inventory_viewer", "billing_manager", "inventory_manager", "super_admin", "org_admin" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported role: " + role);
        };

        if (!CANONICAL_ROLES.contains(canonical)) {
            throw new IllegalArgumentException("Unsupported role: " + role);
        }
        return canonical;
    }

    private Tenant createUniqueTenantForUser(String businessName, String email, SignupRequest request) {
        String normalizedBusinessName = businessName == null ? "Default Business" : businessName.trim();
        if (normalizedBusinessName.isBlank()) {
            normalizedBusinessName = "Default Business";
        }

        String tenantName = normalizedBusinessName;
        String tenantCode = buildUniqueTenantCode(tenantName, email);

        Tenant tenant = new Tenant();
        tenant.setName(tenantName);
        tenant.setCode(tenantCode);
        tenant.setAddress(trimToNull(request.getAddress()));
        tenant.setCity(trimToNull(request.getCity()));
        tenant.setPincode(trimToNull(request.getPincode()));
        tenant.setState(withinLimit(trimToNull(request.getState()), 100));
        tenant.setGstin(validGstin(request.getGstin()));
        tenant.setPhone(withinLimit(trimToNull(request.getPhone()), 32));
        tenant.setEmail(email);
        return tenantRepository.save(tenant);
    }

    private String validGstin(String value) {
        String gstin = trimToNull(value);
        if (gstin == null) {
            return null;
        }
        String normalized = gstin.toUpperCase(Locale.ROOT);
        return normalized.matches("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]")
                ? normalized : null;
    }

    private String withinLimit(String value, int maxLength) {
        return value != null && value.length() <= maxLength ? value : null;
    }

    private String buildUniqueTenantCode(String businessName, String email) {
        String base = businessName == null ? "TEN" : businessName.replaceAll("[^A-Za-z0-9]", "");
        base = base == null || base.isBlank() ? "TEN" : base.toUpperCase();

        String identity = email == null ? String.valueOf(System.currentTimeMillis()) : email.trim();
        int hash = Math.abs((identity + "-" + businessName).hashCode()) % 9000;
        int suffix = 1000 + hash;

        String prefix = base.length() > 4 ? base.substring(0, 4) : base;
        if (prefix.length() < 4) {
            prefix = (prefix + "TEN").substring(0, 4);
        }

        return prefix + String.valueOf(suffix).substring(Math.max(0, String.valueOf(suffix).length() - 4));
    }
}