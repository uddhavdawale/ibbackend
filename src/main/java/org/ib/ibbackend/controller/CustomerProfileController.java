package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.CustomerProfileRequest;
import org.ib.ibbackend.entity.CustomerProfile;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.service.CustomerProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping({"/api/customers", "/api/customer_profiles"})
public class CustomerProfileController {

    private final CustomerProfileService customerProfileService;

    public CustomerProfileController(CustomerProfileService customerProfileService) {
        this.customerProfileService = customerProfileService;
    }

    @PostMapping
    public ResponseEntity<CustomerProfile> upsertCustomer(@RequestBody CustomerProfileRequest request) {
        Long tenantId = request != null && request.getTenantId() != null ? request.getTenantId() : TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        if (request != null) {
            request.setTenantId(tenantId);
        }
        return ResponseEntity.ok(customerProfileService.createOrUpdateCustomer(request));
    }

    @GetMapping
    public ResponseEntity<List<CustomerProfile>> listCustomers() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        return ResponseEntity.ok(customerProfileService.listByTenant(tenantId));
    }

    @GetMapping("/search")
    public ResponseEntity<List<CustomerProfile>> searchCustomers(@RequestParam(required = false) String query) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        return ResponseEntity.ok(customerProfileService.searchByTenant(tenantId, query));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerProfile> getCustomer(@PathVariable Long id) {
        return customerProfileService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
