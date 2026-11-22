package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.Organization;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.security.UserRolePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class InvoiceIssuerResolver {
    private InvoiceIssuerResolver() {
    }

    public static Snapshot resolve(User user, String requestedType) {
        return resolve(user, requestedType, false);
    }

    public static Snapshot resolve(User user, String requestedType, boolean gstInvoice) {
        if (user == null || user.getTenant() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated store is required");
        }

        String type = requestedType == null || requestedType.isBlank()
                ? "STORE"
                : requestedType.trim().toUpperCase(Locale.ROOT);
        Tenant tenant = user.getTenant();
        String storeGstin = resolveStoreGstin(user);
        if (gstInvoice && storeGstin == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A valid store GSTIN is required for GST invoices; use non-GST billing or update the store profile");
        }

        if ("STORE".equals(type)) {
            boolean storeProfileOwner = isStoreProfileOwner(user.getRole());
            String name = hasText(tenant.getName()) ? tenant.getName() : user.getBusinessName();
            return new Snapshot(
                    type,
                    hasText(name) ? name.trim() : null,
                    storeGstin,
                    formatAddress(
                            firstNonBlank(tenant.getAddress(), storeProfileOwner ? user.getAddress() : null),
                            firstNonBlank(tenant.getCity(), storeProfileOwner ? user.getCity() : null),
                            tenant.getState(),
                            firstNonBlank(tenant.getPincode(), storeProfileOwner ? user.getPincode() : null)),
                    contactValue(firstNonBlank(tenant.getPhone(), storeProfileOwner ? user.getPhone() : null), 32),
                    contactValue(firstNonBlank(tenant.getEmail(), storeProfileOwner ? user.getEmail() : null), 255),
                    tenant.getState());
        }

        if ("ORGANIZATION".equals(type)) {
            Organization organization = tenant.getOrganization();
            if (organization == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "This store is not assigned to an organization");
            }
            if (!hasText(organization.getName())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Organization name is not configured");
            }
            String gstin = normalizeGstin(organization.getGstin());
            String address = formatAddress(
                    organization.getAddress(),
                    organization.getCity(),
                    organization.getState(),
                    organization.getPincode());
            if (gstInvoice && (!isValidGstin(gstin) || !hasText(organization.getAddress()))) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Organization GSTIN and registered address are required for GST invoices");
            }
            return new Snapshot(
                    type,
                    organization.getName().trim(),
                    gstin,
                    address,
                    organization.getPhone(),
                    organization.getEmail(),
                    organization.getState());
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issuerType must be STORE or ORGANIZATION");
    }

    public static boolean hasValidStoreGstin(User user) {
        return user != null && user.getTenant() != null && resolveStoreGstin(user) != null;
    }

    private static String resolveStoreGstin(User user) {
        Tenant tenant = user.getTenant();
        boolean storeProfileOwner = isStoreProfileOwner(user.getRole());
        return normalizeGstin(firstNonBlank(
                tenant.getGstin(),
                storeProfileOwner ? user.getGstin() : null));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeGstin(String gstin) {
        if (!hasText(gstin)) {
            return null;
        }
        String normalized = gstin.trim().toUpperCase(Locale.ROOT);
        return isValidGstin(normalized) ? normalized : null;
    }

    private static String contactValue(String value, int maxLength) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : null;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return hasText(preferred) ? preferred : fallback;
    }

    private static boolean isStoreProfileOwner(String role) {
        return switch (UserRolePolicy.normalize(role)) {
            case "store_admin", "retailer", "owner", "admin", "shop_owner" -> true;
            default -> false;
        };
    }

    private static boolean isValidGstin(String gstin) {
        return gstin != null
                && gstin.matches("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]");
    }

    private static String formatAddress(String... parts) {
        String address = Stream.of(parts)
                .filter(InvoiceIssuerResolver::hasText)
                .map(String::trim)
                .collect(Collectors.joining(", "));
        return address.isEmpty() ? null : address;
    }

    public record Snapshot(
            String type,
            String name,
            String gstin,
            String address,
            String phone,
            String email,
            String state) {
    }
}
