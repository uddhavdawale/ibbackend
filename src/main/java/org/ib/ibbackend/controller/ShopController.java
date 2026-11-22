package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.TenantAsset;
import org.ib.ibbackend.entity.TenantAssetType;
import org.ib.ibbackend.entity.Organization;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.TenantAssetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/shop")
public class ShopController {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final TenantAssetService tenantAssetService;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    @Autowired
    public ShopController(UserRepository userRepository,
                          TenantRepository tenantRepository,
                          TenantAssetService tenantAssetService) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.tenantAssetService = tenantAssetService;
    }

    public ShopController(UserRepository userRepository) {
        this(userRepository, null, null);
    }

    @GetMapping({"", "/", "/me"})
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getCurrentShop() {
        User user = currentUser();
        Tenant tenant = tenantFor(user);
        Long tenantId = tenant != null ? (long) tenant.getId() : TenantContext.getCurrentTenant();
        Integer tenantIdLegacy = tenantId != null ? Math.toIntExact(tenantId) : null;
        String businessName = user.getBusinessName();
        if (businessName == null || businessName.isBlank()) {
            businessName = tenant != null && tenant.getName() != null && !tenant.getName().isBlank()
                    ? tenant.getName()
                    : "Business";
        }

        Map<String, Object> shop = new HashMap<>();
        shop.put("id", user.getId());
        shop.put("tenantId", tenantIdLegacy);
        shop.put("tenantCode", tenant != null ? tenant.getCode() : null);
        shop.put("tenantName", tenant != null ? tenant.getName() : businessName);
        shop.put("businessName", businessName);
        shop.put("shopName", businessName);
        shop.put("displayName", businessName);
        shop.put("ownerName", user.getOwnerName());
        shop.put("email", firstNonBlank(tenant != null ? tenant.getEmail() : null, user.getEmail()));
        shop.put("phone", firstNonBlank(tenant != null ? tenant.getPhone() : null, user.getPhone()));
        shop.put("gstin", firstNonBlank(tenant != null ? tenant.getGstin() : null, user.getGstin()));
        shop.put("pan", user.getPan());
        shop.put("role", user.getRole());
        shop.put("address", firstNonBlank(tenant != null ? tenant.getAddress() : null, user.getAddress()));
        shop.put("shopAddress", firstNonBlank(tenant != null ? tenant.getAddress() : null, user.getAddress()));
        shop.put("city", firstNonBlank(tenant != null ? tenant.getCity() : null, user.getCity()));
        shop.put("state", tenant != null ? tenant.getState() : null);
        shop.put("pincode", firstNonBlank(tenant != null ? tenant.getPincode() : null, user.getPincode()));

        Organization organization = user.getTenant() == null ? null : user.getTenant().getOrganization();
        Map<String, Object> organizationSummary = null;
        if (organization != null) {
            organizationSummary = new LinkedHashMap<>();
            organizationSummary.put("id", organization.getId());
            organizationSummary.put("name", organization.getName());
            organizationSummary.put("gstin", organization.getGstin());
            organizationSummary.put("phone", organization.getPhone());
            organizationSummary.put("email", organization.getEmail());
            organizationSummary.put("address", organization.getAddress());
            organizationSummary.put("city", organization.getCity());
            organizationSummary.put("state", organization.getState());
            organizationSummary.put("pincode", organization.getPincode());
            organizationSummary.put("registeredAddress", formatAddress(
                    organization.getAddress(),
                    organization.getCity(),
                    organization.getState(),
                    organization.getPincode()));
        }
        shop.put("organization", organizationSummary);
        shop.put("organizationAssigned", organization != null);

        Set<TenantAssetType> availableAssets = tenant != null && tenantAssetService != null
                ? tenantAssetService.availableTypes(tenant)
                : Set.of();
        boolean hasLogo = availableAssets.contains(TenantAssetType.LOGO);
        shop.put("hasLogo", hasLogo);
        shop.put("logoUrl", hasLogo ? "/api/shop/assets/logo" : null);
        shop.put("hasGstCertificate", availableAssets.contains(TenantAssetType.GST_CERTIFICATE));
        shop.put("hasPanCard", availableAssets.contains(TenantAssetType.PAN_CARD));
        shop.put("hasAadhaarCard", availableAssets.contains(TenantAssetType.AADHAAR_CARD));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("shop", shop);
        return ResponseEntity.ok(response);
    }

    @PutMapping(value = "/profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<Map<String, Object>> updateCurrentShop(
            @RequestParam(value = "address", required = false) String address,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "pincode", required = false) String pincode,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "gstin", required = false) String gstin,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "storeEmail", required = false) String storeEmail,
            @RequestParam(value = "logo", required = false) MultipartFile logo,
            @RequestParam(value = "gstCertificate", required = false) MultipartFile gstCertificate,
            @RequestParam(value = "panCard", required = false) MultipartFile panCard,
            @RequestParam(value = "aadhaarCard", required = false) MultipartFile aadhaarCard) {
        User user = requireRetailer();
        Tenant tenant = requireTenant(user);

        validateLength(address, 500, "address");
        validateLength(city, 100, "city");
        validateLength(pincode, 20, "pincode");
        validateLength(state, 100, "state");
        validateLength(gstin, 15, "gstin");
        validateLength(phone, 32, "phone");
        validateLength(storeEmail, 255, "storeEmail");

        boolean profileChanged = address != null || city != null || pincode != null
                || state != null || gstin != null || phone != null || storeEmail != null;
        if (address != null) {
            tenant.setAddress(trimToNull(address));
            user.setAddress(trimToNull(address));
        }
        if (city != null) {
            tenant.setCity(trimToNull(city));
            user.setCity(trimToNull(city));
        }
        if (pincode != null) {
            tenant.setPincode(trimToNull(pincode));
            user.setPincode(trimToNull(pincode));
        }
        if (state != null) {
            tenant.setState(trimToNull(state));
        }
        if (gstin != null) {
            String normalizedGstin = trimToNull(gstin);
            if (normalizedGstin != null && !normalizedGstin.toUpperCase(Locale.ROOT).matches(
                    "[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid store GSTIN");
            }
            tenant.setGstin(normalizedGstin == null ? null : normalizedGstin.toUpperCase(Locale.ROOT));
            user.setGstin(tenant.getGstin());
        }
        if (phone != null) {
            tenant.setPhone(trimToNull(phone));
            user.setPhone(tenant.getPhone());
        }
        if (storeEmail != null) {
            String normalizedEmail = trimToNull(storeEmail);
            if (normalizedEmail != null && !normalizedEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid store email");
            }
            tenant.setEmail(normalizedEmail);
        }

        if (profileChanged) {
            tenantRepository.save(tenant);
            userRepository.save(user);
        }
        tenantAssetService.storeIfProvided(tenant, TenantAssetType.LOGO, logo);
        tenantAssetService.storeIfProvided(tenant, TenantAssetType.GST_CERTIFICATE, gstCertificate);
        tenantAssetService.storeIfProvided(tenant, TenantAssetType.PAN_CARD, panCard);
        tenantAssetService.storeIfProvided(tenant, TenantAssetType.AADHAAR_CARD, aadhaarCard);
        if (auditLogService != null && (logo != null || gstCertificate != null || panCard != null || aadhaarCard != null)) {
            auditLogService.success(
                    "TENANT_DOCUMENT_UPLOAD",
                    String.valueOf(tenant.getId()),
                    "Retailer shop profile documents updated");
        }

        return ResponseEntity.ok(Map.of("success", true, "shop", getCurrentShop().getBody().get("shop")));
    }

    @GetMapping("/assets/{type}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> getCurrentShopAsset(@PathVariable String type) {
        User user = requireRetailer();
        Tenant tenant = requireTenant(user);
        TenantAssetType assetType;
        try {
            assetType = TenantAssetType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop asset not found");
        }

        Optional<TenantAsset> asset = tenantAssetService.find(tenant, assetType);
        TenantAsset storedAsset = asset.orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop asset not found"));
        if (auditLogService != null) {
            auditLogService.success(
                    "TENANT_DOCUMENT_ACCESS",
                    String.valueOf(tenant.getId()),
                    "Retailer accessed shop profile document: " + assetType.name());
        }
        MediaType contentType = MediaType.parseMediaType(storedAsset.getContentType());
        String dispositionType = assetType == TenantAssetType.LOGO ? "inline" : "attachment";

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.builder(dispositionType).filename(assetType.name().toLowerCase(Locale.ROOT)).build().toString())
                .body(tenantAssetService.decrypt(storedAsset));
    }

    private User currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AccessDeniedException("Access denied");
        }
        return userRepository.findByEmail(authentication.getName())
                .or(() -> userRepository.findByUsername(authentication.getName()))
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
    }

    private User requireRetailer() {
        User user = currentUser();
        if (!UserRolePolicy.isStoreAdmin(user.getRole())) {
            throw new AccessDeniedException("Only a retailer can manage shop profile documents");
        }
        return user;
    }

    private Tenant requireTenant(User user) {
        Tenant tenant = user.getTenant();
        if (tenant != null) {
            return tenant;
        }
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantRepository == null) {
            throw new AccessDeniedException("Access denied");
        }
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
    }

    private Tenant tenantFor(User user) {
        if (user.getTenant() != null || TenantContext.getCurrentTenant() == null || tenantRepository == null) {
            return user.getTenant();
        }
        return tenantRepository.findById(TenantContext.getCurrentTenant()).orElse(null);
    }

    private String trimToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validateLength(String value, int maxLength, String fieldName) {
        if (value != null && value.trim().length() > maxLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, fieldName + " must be at most " + maxLength + " characters");
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private String formatAddress(String... parts) {
        return java.util.Arrays.stream(parts)
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
