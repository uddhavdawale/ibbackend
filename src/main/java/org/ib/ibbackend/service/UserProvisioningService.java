package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.SignupRequest;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.TenantRepository;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class UserProvisioningService {

    private static final Set<String> ADMIN_CAPABLE_ROLES = new HashSet<>(Arrays.asList(
            "store_admin", "owner", "admin", "shop_owner", "retailer", "super_admin", "org_admin"
    ));

    private static final Set<String> CANONICAL_ROLES = new HashSet<>(List.of(
            "store_admin", "store_manager", "store_billing_viewer", "store_inventory_viewer", "super_admin", "org_admin"
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
        return request.getEmail().trim();
    }

    public User provisionUser(SignupRequest request, String normalizedEmail, Optional<User> creatorOpt) {
        return creatorOpt
                .map(creator -> buildChildUserFromCreator(creator, request, normalizedEmail))
                .orElseGet(() -> buildRootUser(request, normalizedEmail));
    }

    private User buildChildUserFromCreator(User creator, SignupRequest request, String normalizedEmail) {
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

        User user = buildCommonUserFields(request, normalizedEmail);

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
        user.setRole(normalizeRole(request.getRole() == null || request.getRole().isBlank() ? "store_manager" : request.getRole()));
        return user;
    }

    private User buildRootUser(SignupRequest request, String normalizedEmail) {
        User user = buildCommonUserFields(request, normalizedEmail);
        String businessName = resolveBusinessName(request.getBusinessName());

        user.setBusinessName(businessName);
        user.setOwnerName(request.getOwnerName() == null || request.getOwnerName().isBlank() ? "Owner" : request.getOwnerName().trim());
        user.setRole(normalizeRole(request.getRole() == null || request.getRole().isBlank() ? "store_admin" : request.getRole()));
        user.setTenant(createUniqueTenantForUser(businessName, normalizedEmail));
        return user;
    }

    private User buildCommonUserFields(SignupRequest request, String normalizedEmail) {
        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordSecurityService.hashNewPassword(request.getPasswordHash()));
        user.setPhone(request.getPhone());
        user.setGstin(request.getGstin());
        user.setPan(request.getPan());
        user.setAddress(request.getAddress());
        user.setCity(request.getCity());
        user.setPincode(request.getPincode());
        return user;
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
            case "store_billing_viwer" -> "store_billing_viewer";
            case "store_inventory_viwer" -> "store_inventory_viewer";
            case "store_admin", "store_manager", "store_billing_viewer", "store_inventory_viewer", "super_admin", "org_admin" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported role: " + role);
        };

        if (!CANONICAL_ROLES.contains(canonical)) {
            throw new IllegalArgumentException("Unsupported role: " + role);
        }
        return canonical;
    }

    private Tenant createUniqueTenantForUser(String businessName, String email) {
        String normalizedBusinessName = businessName == null ? "Default Business" : businessName.trim();
        if (normalizedBusinessName.isBlank()) {
            normalizedBusinessName = "Default Business";
        }

        String tenantName = normalizedBusinessName;
        String tenantCode = buildUniqueTenantCode(tenantName, email);

        Tenant tenant = new Tenant();
        tenant.setName(tenantName);
        tenant.setCode(tenantCode);
        return tenantRepository.save(tenant);
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