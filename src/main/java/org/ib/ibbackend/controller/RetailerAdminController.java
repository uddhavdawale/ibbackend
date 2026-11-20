package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.AuditLog;
import org.ib.ibbackend.entity.RetailerStatus;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.AuditLogRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.ib.ibbackend.service.RetailerAdminService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController

@RequestMapping("/api/admin")
public class RetailerAdminController {

    private final RetailerAdminService retailerAdminService;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    public RetailerAdminController(RetailerAdminService retailerAdminService,
                                  UserRepository userRepository,
                                  AuditLogRepository auditLogRepository) {
        this.retailerAdminService = retailerAdminService;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard() {
        User actor = requireAdmin();
        if (actor.getTenant() == null) {
            throw new AccessDeniedException("Current user has no tenant");
        }

        List<User> tenantUsers = userRepository.findTenantUsersForManagement(
                null, true,
                PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "id"))).getContent();

        long totalRetailers = tenantUsers.stream().filter(this::isRetailerRecord).count();
        long activeRetailers = tenantUsers.stream()
                .filter(this::isRetailerRecord)
                .filter(user -> user.getApprovalStatus() == RetailerStatus.APPROVED)
                .count();
        long pendingApprovals = tenantUsers.stream()
                .filter(this::isRetailerRecord)
                .filter(user -> user.getApprovalStatus() == RetailerStatus.PENDING_APPROVAL
                        || user.getApprovalStatus() == RetailerStatus.MORE_INFO_REQUIRED)
                .count();
        long approvalRate = totalRetailers == 0 ? 0 : Math.round((activeRetailers * 100.0d) / totalRetailers);

        List<Map<String, Object>> recentRequests = tenantUsers.stream()
                .filter(this::isRetailerRecord)
                .sorted((a, b) -> Long.compare(b.getId() != null ? b.getId() : 0L, a.getId() != null ? a.getId() : 0L))
                .limit(5)
                .map(user -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", user.getId());
                    item.put("businessName", user.getBusinessName());
                    item.put("ownerName", user.getOwnerName());
                    item.put("status", user.getApprovalStatus() != null ? user.getApprovalStatus().name() : "PENDING_APPROVAL");
                    return item;
                })
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalRetailers", totalRetailers);
        data.put("activeRetailers", activeRetailers);
        data.put("pendingApprovals", pendingApprovals);
        data.put("approvalRate", approvalRate);
        data.put("averageApprovalTime", "3.6 days");
        data.put("recentRequests", recentRequests);
        data.put("recentActivity", List.of());
        data.put("growthTrend", List.of());
        data.put("cityDistribution", List.of());
        data.put("stateDistribution", List.of());
        data.put("rejectionReasons", List.of());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        response.put("summary", data);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/retailers")
    public ResponseEntity<Map<String, Object>> listRetailers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status) {
            requireAdmin();

            Page<User> pageResult = retailerAdminService.listRetailersForPlatformAdmin(
                search, status, page, size);

        List<Map<String, Object>> data = pageResult.getContent().stream()
                .map(this::toRetailerSummary)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        response.put("items", data);
        response.put("page", pageResult.getNumber());
        response.put("size", pageResult.getSize());
        response.put("totalItems", pageResult.getTotalElements());
        response.put("totalPages", pageResult.getTotalPages());
        response.put("hasMore", pageResult.hasNext());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/retailers/{id}")
    public ResponseEntity<Map<String, Object>> getRetailer(@PathVariable Long id) {
        requireAdmin();
        User retailer = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Retailer not found"));
        return ResponseEntity.ok(toRetailerDetail(retailer));
    }

    @PatchMapping("/retailers/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveRetailer(@PathVariable Long id,
                                                             @RequestBody(required = false) Map<String, String> payload) {
        User actor = requireAdmin();
        String note = payload != null ? payload.get("note") : null;
        User updated = retailerAdminService.approveRetailer(id, note, actor.getEmail());
        return successResponse("Retailer approved successfully", "APPROVED", updated);
    }

    @PatchMapping("/retailers/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectRetailer(@PathVariable Long id,
                                                            @RequestBody(required = false) Map<String, String> payload) {
        User actor = requireAdmin();
        String note = payload != null ? payload.get("note") : null;
        User updated = retailerAdminService.rejectRetailer(id, note, actor.getEmail());
        return successResponse("Retailer rejected successfully", "REJECTED", updated);
    }

    @PatchMapping("/retailers/{id}/request-info")
    public ResponseEntity<Map<String, Object>> requestMoreInfoRetailer(@PathVariable Long id,
                                                                       @RequestBody(required = false) Map<String, String> payload) {
        User actor = requireAdmin();
        String note = payload != null ? payload.get("note") : null;
        User updated = retailerAdminService.requestMoreInfo(id, note, actor.getEmail());
        return successResponse("More information requested", "MORE_INFO_REQUIRED", updated);
    }

    @PatchMapping("/retailers/{id}/block")
    public ResponseEntity<Map<String, Object>> blockRetailer(@PathVariable Long id,
                                                           @RequestBody(required = false) Map<String, String> payload) {
        User actor = requireAdmin();
        String note = payload != null ? payload.get("note") : null;
        User updated = retailerAdminService.blockRetailer(id, note, actor.getEmail());
        return successResponse("Retailer blocked successfully", "BLOCKED", updated);
    }

    @PatchMapping("/retailers/{id}/unblock")
    public ResponseEntity<Map<String, Object>> unblockRetailer(@PathVariable Long id,
                                                             @RequestBody(required = false) Map<String, String> payload) {
        User actor = requireAdmin();
        String note = payload != null ? payload.get("note") : null;
        User updated = retailerAdminService.unblockRetailer(id, note, actor.getEmail());
        return successResponse("Retailer unblocked successfully", "APPROVED", updated);
    }

    @PatchMapping("/retailers/{id}/disable")
    public ResponseEntity<Map<String, Object>> disableRetailer(@PathVariable Long id,
                                                             @RequestBody(required = false) Map<String, String> payload) {
        User actor = requireAdmin();
        String note = payload != null ? payload.get("note") : null;
        User updated = retailerAdminService.disableRetailer(id, note, actor.getEmail());
        return successResponse("Retailer disabled successfully", "DISABLED", updated);
    }

    @PatchMapping("/retailers/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableRetailer(@PathVariable Long id,
                                                            @RequestBody(required = false) Map<String, String> payload) {
        User actor = requireAdmin();
        String note = payload != null ? payload.get("note") : null;
        User updated = retailerAdminService.enableRetailer(id, note, actor.getEmail());
        return successResponse("Retailer enabled successfully", "APPROVED", updated);
    }

    @DeleteMapping("/retailers/{id}")
    public ResponseEntity<Map<String, Object>> deleteRetailer(@PathVariable Long id,
                                                            @RequestBody(required = false) Map<String, String> payload) {
        User actor = requireAdmin();
        String note = payload != null ? payload.get("note") : null;
        User updated = retailerAdminService.softDeleteRetailer(id, note, actor.getEmail());
        return successResponse("Retailer deleted successfully", "DELETED", updated);
    }

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status) {
        User current = requireAdmin();
        Page<User> pageResult;

        if (UserRolePolicy.isSystemAdmin(current.getRole())) {
            pageResult = userRepository.findAllUsersForManagement(
                    search == null ? null : search.trim(),
                    true,
                    PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size <= 0 ? 20 : size, 100)),
                            Sort.by(Sort.Direction.DESC, "id"))
            );
        } else {
            if (current.getTenant() == null) {
                throw new AccessDeniedException("Current user has no tenant");
            }
            pageResult = userRepository.findTenantUsersForManagement1(
                    current.getTenant().getId(),
                    search,
                    true,
                    PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size <= 0 ? 20 : size, 100)),
                            Sort.by(Sort.Direction.DESC, "id"))
            );
        }

        List<Map<String, Object>> data = pageResult.getContent().stream()
                .map(this::toUserSummary)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        response.put("items", data);
        response.put("page", pageResult.getNumber());
        response.put("size", pageResult.getSize());
        response.put("totalItems", pageResult.getTotalElements());
        response.put("totalPages", pageResult.getTotalPages());
        response.put("hasMore", pageResult.hasNext());
        response.put("platformWide", UserRolePolicy.isSystemAdmin(current.getRole()));
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/users/{id}/status")
    public ResponseEntity<Map<String, Object>> updateUserStatus(@PathVariable Long id,
                                                               @RequestBody Map<String, Object> payload) {
        User actor = requireAdmin();
        if (actor.getId() != null && actor.getId().equals(id)) {
            throw new AccessDeniedException("Self-status changes are blocked");
        }

        boolean disabled;
        if (payload != null && payload.containsKey("disabled")) {
            Object value = payload.get("disabled");
            if (!(value instanceof Boolean)) {
                return ResponseEntity.badRequest().body(
                        Map.of("success", false, "message", "disabled must be a boolean"));
            }
            disabled = (Boolean) value;
        } else if (payload != null && payload.containsKey("enabled")) {
            Object value = payload.get("enabled");
            if (!(value instanceof Boolean)) {
                return ResponseEntity.badRequest().body(
                        Map.of("success", false, "message", "enabled must be a boolean"));
            }
            disabled = !(Boolean) value;
        } else if (payload != null && payload.containsKey("status")) {
            String status = String.valueOf(payload.get("status")).trim();
            String normalized = status.toUpperCase(Locale.ROOT);
            if ("ACTIVE".equals(normalized) || "APPROVED".equals(normalized)) {
                disabled = false;
            } else if ("DISABLED".equals(normalized) || "INACTIVE".equals(normalized)
                    || "DEACTIVATED".equals(normalized) || "BLOCKED".equals(normalized)
                    || "SUSPENDED".equals(normalized)) {
                disabled = true;
            } else {
                return ResponseEntity.badRequest().body(
                        Map.of("success", false, "message", "Unsupported user status"));
            }
        } else {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "status or disabled flag is required"));
        }

        Object noteValue = payload != null ? payload.get("note") : null;
        String note = noteValue instanceof String ? (String) noteValue : null;
        User saved = disabled
                ? retailerAdminService.disableRetailer(id, note, actor.getEmail())
                : retailerAdminService.enableRetailer(id, note, actor.getEmail());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", disabled ? "User disabled successfully" : "User enabled successfully");
        response.put("status", disabled ? "DISABLED" : "ACTIVE");
        response.put("user", toUserSummary(saved));
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/users/{id}/role")
    public ResponseEntity<Map<String, Object>> updateUserRole(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> payload) {
        User actor = requireAdmin();
        if (payload == null || payload.get("role") == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "role is required"));
        }

        User target = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (actor.getId().equals(target.getId())) {
            throw new AccessDeniedException("Self-role changes are blocked");
        }
        String role = String.valueOf(payload.get("role")).trim();
        target.setRole(role);
        userRepository.save(target);
        return successResponse("User role updated successfully", role, target);
    }

    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable Long id,
                                                           @RequestBody(required = false) Map<String, String> payload) {
        requireAdmin();
        userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "Password reset workflow initiated");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<Map<String, Object>> auditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        User current = requireAdmin();
        if (current.getTenant() == null) {
            throw new AccessDeniedException("Current user has no tenant");
        }

        Page<AuditLog> pageResult = auditLogRepository.findForAuditView(
               Long.valueOf( current.getTenant().getId()),
                Instant.EPOCH,
                Instant.now().plusSeconds(60L * 60L * 24L * 3650L),
                "",
                "",
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size <= 0 ? 20 : size, 100)),
                        Sort.by(Sort.Direction.DESC, "occurredAt"))
        );

        List<Map<String, Object>> data = new ArrayList<>();
        for (AuditLog entry : pageResult.getContent()) {
            String actor = userRepository.findById(entry.getUserId())
                    .map(User::getOwnerName)
                    .orElse(entry.getActorRole() != null ? entry.getActorRole() : "System Admin");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "AUD-" + entry.getId());
            item.put("actor", actor);
            item.put("action", entry.getAction());
            item.put("target", entry.getResourceType() + " #" + entry.getResourceId());
            item.put("time", entry.getOccurredAt().toString());
            item.put("status", entry.getOutcome());
            data.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        response.put("items", data);
        response.put("page", pageResult.getNumber());
        response.put("size", pageResult.getSize());
        response.put("totalItems", pageResult.getTotalElements());
        response.put("totalPages", pageResult.getTotalPages());
        response.put("hasMore", pageResult.hasNext());
        return ResponseEntity.ok(response);
    }

    private User requireAdmin() {
        String principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getName();
        if (principal == null || principal.isBlank()) {
            throw new AccessDeniedException("Access denied");
        }

        User user = userRepository.findByEmail(principal)
                .orElseGet(() -> userRepository.findByUsername(principal)
                        .orElseThrow(() -> new AccessDeniedException("Access denied")));

        if (!UserRolePolicy.canAccessPlatformAdmin(user.getRole())) {
            throw new AccessDeniedException("Only system admins can access platform retailer admin APIs");
        }

        return user;
    }

    private ResponseEntity<Map<String, Object>> successResponse(String message, String status, User user) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("status", status);
        response.put("user", toUserSummary(user));
        return ResponseEntity.ok(response);
    }

    private boolean isRetailerRecord(User user) {
        if (user == null) {
            return false;
        }
        return user.getRole() != null && ("retailer".equalsIgnoreCase(user.getRole())
                || "store_admin".equalsIgnoreCase(user.getRole())
                || "owner".equalsIgnoreCase(user.getRole())
                || "shop_owner".equalsIgnoreCase(user.getRole())
                || "admin".equalsIgnoreCase(user.getRole())
                || "org_admin".equalsIgnoreCase(user.getRole())
                || "super_admin".equalsIgnoreCase(user.getRole())
                || "system_admin".equalsIgnoreCase(user.getRole())
                || "system_administrator".equalsIgnoreCase(user.getRole()));
    }

    private Map<String, Object> toRetailerSummary(User user) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", user.getId());
        item.put("businessName", user.getBusinessName());
        item.put("ownerName", user.getOwnerName());
        item.put("email", user.getEmail());
        item.put("phone", user.getPhone());
        item.put("gstin", user.getGstin());
        item.put("city", user.getCity());
        item.put("state", user.getAddress());
        item.put("status", user.getApprovalStatus() != null ? user.getApprovalStatus().name() : "PENDING_APPROVAL");
        item.put("businessType", "Retail Store");
        item.put("assignedAdmin", "Asha");
        item.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
        return item;
    }

    private Map<String, Object> toUserSummary(User user) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", user.getId());
        item.put("name", user.getOwnerName());
        item.put("email", user.getEmail());
        item.put("role", user.getRole());
        item.put("status", user.isDisabled() ? "DISABLED" : "ACTIVE");
        item.put("retailer", user.getBusinessName());
        item.put("lastLogin", user.getLastAdminActionAt() != null ? user.getLastAdminActionAt().toString() : null);
        return item;
    }

    private Map<String, Object> toRetailerDetail(User user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", user.getId());
        payload.put("businessName", user.getBusinessName());
        payload.put("ownerName", user.getOwnerName());
        payload.put("email", user.getEmail());
        payload.put("phone", user.getPhone());
        payload.put("gstin", user.getGstin());
        payload.put("status", user.getApprovalStatus() != null ? user.getApprovalStatus().name() : "PENDING_APPROVAL");
        payload.put("documents", List.of("GST Certificate", "PAN Card", "Business Proof"));
        payload.put("reviewNote", user.getAdminNote());
        payload.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
        payload.put("success", true);
        return payload;
    }
}
