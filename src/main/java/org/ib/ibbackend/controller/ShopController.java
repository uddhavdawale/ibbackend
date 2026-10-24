package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/shop")
public class ShopController {

    private final UserRepository userRepository;

    public ShopController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping({"", "/", "/me"})
    public ResponseEntity<Map<String, Object>> getCurrentShop() {
        String email = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getName();

        if (email == null || email.isBlank()) {
            throw new AccessDeniedException("Access denied");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));

        Tenant tenant = user.getTenant();
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
        shop.put("email", user.getEmail());
        shop.put("phone", user.getPhone());
        shop.put("gstin", user.getGstin());
        shop.put("pan", user.getPan());
        shop.put("role", user.getRole());
        shop.put("address", user.getAddress());
        shop.put("shopAddress", user.getAddress());
        shop.put("city", user.getCity());
        shop.put("pincode", user.getPincode());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("shop", shop);
        return ResponseEntity.ok(response);
    }
}
