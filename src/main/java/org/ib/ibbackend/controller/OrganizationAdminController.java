package org.ib.ibbackend.controller;

import jakarta.validation.Valid;
import org.ib.ibbackend.dto.OrganizationRequest;
import org.ib.ibbackend.entity.Organization;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.OrganizationRepository;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.ib.ibbackend.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@RestController
@RequestMapping("/api/admin")
public class OrganizationAdminController {
    private static final int MAX_PAGE_SIZE = 100;

    private final OrganizationRepository organizationRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public OrganizationAdminController(
            OrganizationRepository organizationRepository,
            TenantRepository tenantRepository,
            UserRepository userRepository,
            AuditLogService auditLogService) {
        this.organizationRepository = organizationRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/organizations")
    @Transactional
    public Map<String, Object> createOrganization(@Valid @RequestBody OrganizationRequest request) {
        requireSystemAdmin();
        Organization organization = new Organization();
        organization.setName(request.getName().trim());
        organization.setAddress(trimToNull(request.getAddress()));
        organization.setCity(trimToNull(request.getCity()));
        organization.setPincode(trimToNull(request.getPincode()));
        organization.setState(trimToNull(request.getState()));
        String gstin = trimToNull(request.getGstin());
        if (gstin != null && !gstin.toUpperCase(Locale.ROOT).matches(
                "[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid organization GSTIN");
        }
        organization.setGstin(gstin == null ? null : gstin.toUpperCase(Locale.ROOT));
        organization.setPhone(trimToNull(request.getPhone()));
        String email = trimToNull(request.getEmail());
        if (email != null && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid organization email");
        }
        organization.setEmail(email);
        Organization saved = organizationRepository.save(organization);
        auditLogService.success("ORGANIZATION_CREATE", saved.getId().toString(), "Organization created");
        return organizationResponse(saved);
    }

    @GetMapping("/organizations")
    @Transactional(readOnly = true)
    public Map<String, Object> listOrganizations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireSystemAdmin();
        Page<Organization> results = organizationRepository.findAll(
                PageRequest.of(Math.max(0, page), safeSize(size), Sort.by(Sort.Direction.ASC, "id")));
        List<Map<String, Object>> data = results.getContent().stream()
                .map(this::organizationResponse)
                .toList();
        return pageResponse(results, data);
    }

    @GetMapping("/stores")
    @Transactional(readOnly = true)
    public Map<String, Object> listStores(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search) {
        requireSystemAdmin();
        String safeSearch = search == null ? null : search.trim();
        if (safeSearch != null && safeSearch.length() > 100) {
            safeSearch = safeSearch.substring(0, 100);
        }
        Page<Tenant> results = tenantRepository.searchForOrganizationAssignment(
                safeSearch, PageRequest.of(Math.max(0, page), safeSize(size), Sort.by(Sort.Direction.ASC, "id")));
        List<Map<String, Object>> data = results.getContent().stream()
                .map(this::storeResponse)
                .toList();
        return pageResponse(results, data);
    }

    @PutMapping("/organizations/{organizationId}/stores/{tenantId}")
    @Transactional
    public Map<String, Object> assignStore(
            @PathVariable Long organizationId,
            @PathVariable Long tenantId) {
        requireSystemAdmin();
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
        Tenant tenant = tenantRepository.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found"));
        tenant.setOrganization(organization);
        tenantRepository.save(tenant);
        auditLogService.success(
                "ORGANIZATION_STORE_ASSIGN",
                tenantId.toString(),
                "Store assigned to organization " + organizationId);
        return storeResponse(tenant);
    }

    @DeleteMapping("/organizations/{organizationId}/stores/{tenantId}")
    @Transactional
    public Map<String, Object> unassignStore(
            @PathVariable Long organizationId,
            @PathVariable Long tenantId) {
        requireSystemAdmin();
        tenantRepository.findByIdForUpdate(tenantId).ifPresentOrElse(tenant -> {
            if (tenant.getOrganization() == null
                    || !organizationId.equals(tenant.getOrganization().getId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Store assignment not found");
            }
            tenant.setOrganization(null);
            tenantRepository.save(tenant);
            auditLogService.success(
                    "ORGANIZATION_STORE_UNASSIGN",
                    tenantId.toString(),
                    "Store unassigned from organization " + organizationId);
        }, () -> {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found");
        });
        return Map.of("success", true, "tenantId", tenantId);
    }

    private User requireSystemAdmin() {
        String principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getName();
        if (principal == null || principal.isBlank()) {
            throw new AccessDeniedException("Access denied");
        }
        User user = userRepository.findByEmail(principal)
                .or(() -> userRepository.findByUsername(principal))
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
        if (!UserRolePolicy.isSystemAdmin(user.getRole())) {
            throw new AccessDeniedException("Only system admins can manage organizations and stores");
        }
        return user;
    }

    private int safeSize(int size) {
        return size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    }

    private Map<String, Object> pageResponse(Page<?> page, List<Map<String, Object>> data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        response.put("page", page.getNumber());
        response.put("size", page.getSize());
        response.put("totalItems", page.getTotalElements());
        response.put("totalPages", page.getTotalPages());
        response.put("hasMore", page.hasNext());
        return response;
    }

    private Map<String, Object> organizationResponse(Organization organization) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", organization.getId());
        response.put("name", organization.getName());
        response.put("address", organization.getAddress());
        response.put("city", organization.getCity());
        response.put("pincode", organization.getPincode());
        response.put("state", organization.getState());
        response.put("gstin", organization.getGstin());
        response.put("phone", organization.getPhone());
        response.put("email", organization.getEmail());
        return response;
    }

    private Map<String, Object> storeResponse(Tenant tenant) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenantId", tenant.getId());
        response.put("name", tenant.getName());
        response.put("code", tenant.getCode());
        Organization organization = tenant.getOrganization();
        response.put("organizationId", organization == null ? null : organization.getId());
        response.put("organizationName", organization == null ? null : organization.getName());
        return response;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
