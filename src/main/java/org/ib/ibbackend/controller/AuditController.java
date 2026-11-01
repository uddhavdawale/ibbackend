package org.ib.ibbackend.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.ib.ibbackend.entity.AuditLog;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.AuditLogRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private static final long TENANT_GLOBAL_SCOPE = -1L;
    private static final Instant MIN_FILTER_INSTANT = Instant.EPOCH;
    private static final Instant MAX_FILTER_INSTANT = Instant.parse("9999-12-31T23:59:59Z");
    private static final Set<String> AUDIT_VIEW_ROLES = Set.of(
            "system_administrator",
            "store_admin",
            "audit_related"
    );
    private static final Set<String> AUDIT_EXPORT_ROLES = Set.of(
            "system_administrator",
            "store_admin"
    );
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_SUMMARY_DAYS = 30;
    private static final int MAX_SUMMARY_DAYS = 365;
    private static final int DEFAULT_EXPORT_ROWS = 1000;
    private static final int MAX_EXPORT_ROWS = 5000;

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public AuditController(AuditLogRepository auditLogRepository,
                           UserRepository userRepository,
                           AuditLogService auditLogService) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> getAuditEvents(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "actionType", required = false) String actionType,
            @RequestParam(name = "outcome", required = false) String outcome,
            @RequestParam(name = "tenantId", required = false) Long tenantId,
            HttpServletRequest request
    ) {
        try {
            AccessContext access = requireAuditAccess(request.getRequestURI(), false, tenantId);
            Instant fromTs = withDefault(parseInstantFilter(from, false), MIN_FILTER_INSTANT);
            Instant toTs = withDefault(parseInstantFilter(to, true), MAX_FILTER_INSTANT);
            long scopedTenantId = normalizeTenantScope(access.effectiveTenantId);

            int safePage = Math.max(page, 0);
            int safeSize = clamp(size, 1, MAX_PAGE_SIZE, DEFAULT_PAGE_SIZE);

            Page<AuditLog> rows = auditLogRepository.findForAuditView(
                scopedTenantId,
                    fromTs,
                    toTs,
                    normalizeFilter(actionType),
                    normalizeFilter(outcome),
                    PageRequest.of(safePage, safeSize)
            );

            Map<Long, String> roleByUserId = resolveRoles(rows.getContent());
            List<Map<String, Object>> items = new ArrayList<>();
            for (AuditLog row : rows.getContent()) {
                items.add(toAuditPayload(row, roleByUserId));
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("items", items);
            payload.put("page", rows.getNumber());
            payload.put("size", rows.getSize());
            payload.put("totalItems", rows.getTotalElements());
            payload.put("totalPages", rows.getTotalPages());
            payload.put("scopeTenantId", access.effectiveTenantId);
            payload.put("role", access.normalizedRole);
            payload.put("hasMore", rows.hasNext());

            auditLogService.success("AUDIT_REPORT_VIEW", request.getRequestURI(), "Audit events fetched");
            return secureNoStoreResponse(payload);
        } catch (IllegalArgumentException ex) {
            return secureNoStoreResponse(ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            )));
        }
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getAuditSummary(
            @RequestParam(name = "days", defaultValue = "30") int days,
            @RequestParam(name = "tenantId", required = false) Long tenantId,
            HttpServletRequest request
    ) {
        AccessContext access = requireAuditAccess(request.getRequestURI(), false, tenantId);
        long scopedTenantId = normalizeTenantScope(access.effectiveTenantId);

        int safeDays = clamp(days, 1, MAX_SUMMARY_DAYS, DEFAULT_SUMMARY_DAYS);
        Instant now = Instant.now();
        Instant fromTs = now.minusSeconds((long) safeDays * 24L * 60L * 60L);

        long totalChecks = auditLogRepository.countForAuditScope(scopedTenantId, fromTs, now);
        long exceptionCount = auditLogRepository.countExceptionsForAuditScope(scopedTenantId, fromTs, now);
        long recentAccessEvents = auditLogRepository.countRecentEventsByActions(
            scopedTenantId,
                now.minusSeconds(24L * 60L * 60L),
                accessEventActions()
        );

        double complianceScore = totalChecks == 0
                ? 100.0d
                : Math.max(0.0d, 100.0d - ((double) exceptionCount / (double) totalChecks) * 100.0d);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalChecks", totalChecks);
        summary.put("recentAccessEvents", recentAccessEvents);
        summary.put("exceptionCount", exceptionCount);
        summary.put("complianceScore", BigDecimal.valueOf(complianceScore).setScale(2, RoundingMode.HALF_UP));
        summary.put("windowDays", safeDays);
        summary.put("scopeTenantId", access.effectiveTenantId);
        summary.put("role", access.normalizedRole);

        auditLogService.success("AUDIT_REPORT_VIEW", request.getRequestURI(), "Audit summary fetched");
        return secureNoStoreResponse(summary);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportAuditEvents(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "actionType", required = false) String actionType,
            @RequestParam(name = "outcome", required = false) String outcome,
            @RequestParam(name = "tenantId", required = false) Long tenantId,
            @RequestParam(name = "maxRows", defaultValue = "1000") int maxRows,
            HttpServletRequest request
    ) {
        AccessContext access = requireAuditAccess(request.getRequestURI(), true, tenantId);
        long scopedTenantId = normalizeTenantScope(access.effectiveTenantId);

        Instant fromTs = withDefault(parseInstantFilter(from, false), MIN_FILTER_INSTANT);
        Instant toTs = withDefault(parseInstantFilter(to, true), MAX_FILTER_INSTANT);

        int safeMaxRows = clamp(maxRows, 1, MAX_EXPORT_ROWS, DEFAULT_EXPORT_ROWS);
        Page<AuditLog> rows = auditLogRepository.findForAuditView(
            scopedTenantId,
                fromTs,
                toTs,
                normalizeFilter(actionType),
                normalizeFilter(outcome),
                PageRequest.of(0, safeMaxRows)
        );

        Map<Long, String> roleByUserId = resolveRoles(rows.getContent());
        StringBuilder csv = new StringBuilder();
        csv.append("audit_id,actor_user_id,actor_role,action_type,resource_type,resource_id,tenant_id,event_timestamp,outcome,ip_address,device_context,endpoint,http_method,metadata\n");

        for (AuditLog row : rows.getContent()) {
            csv
                    .append(csvValue(row.getId())).append(',')
                    .append(csvValue(row.getUserId())).append(',')
                    .append(csvValue(resolveActorRole(row, roleByUserId))).append(',')
                    .append(csvValue(row.getAction())).append(',')
                    .append(csvValue(resolveResourceType(row))).append(',')
                    .append(csvValue(row.getResourceId())).append(',')
                    .append(csvValue(row.getTenantId())).append(',')
                    .append(csvValue(row.getOccurredAt())).append(',')
                    .append(csvValue(row.getOutcome())).append(',')
                    .append(csvValue(row.getIpAddress())).append(',')
                    .append(csvValue(row.getUserAgent())).append(',')
                    .append(csvValue(row.getRequestPath())).append(',')
                    .append(csvValue(row.getHttpMethod())).append(',')
                    .append(csvValue(row.getMetadata() != null ? row.getMetadata() : row.getDetail()))
                    .append('\n');
        }

        String fileName = "audit-report-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now()) + ".csv";

        auditLogService.success("EXPORT_DOWNLOAD", request.getRequestURI(), "Audit CSV exported");

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate().cachePrivate().sMaxAge(0, TimeUnit.SECONDS))
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header("X-Export-Truncated", String.valueOf(rows.getTotalElements() > safeMaxRows))
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private AccessContext requireAuditAccess(String endpoint, boolean exportOnly, Long requestedTenantId) {
        User user = resolveCurrentUser();
        String normalizedRole = normalizeRole(user != null ? user.getRole() : null);

        if (user == null || !AUDIT_VIEW_ROLES.contains(normalizedRole)) {
            auditDenied(endpoint, user, normalizedRole, "Role is not allowed for audit access");
            throw new AccessDeniedException("Access denied");
        }

        if (exportOnly && !AUDIT_EXPORT_ROLES.contains(normalizedRole)) {
            auditDenied(endpoint, user, normalizedRole, "Role is not allowed for audit export");
            throw new AccessDeniedException("Access denied");
        }

        Long scopedTenantId;
        if ("system_administrator".equals(normalizedRole)) {
            scopedTenantId = requestedTenantId;
        } else {
            scopedTenantId = resolveTenantId(user);
            if (scopedTenantId == null) {
                auditDenied(endpoint, user, normalizedRole, "Tenant scope missing for audit access");
                throw new AccessDeniedException("Access denied");
            }
            if (requestedTenantId != null && !requestedTenantId.equals(scopedTenantId)) {
                auditDenied(endpoint, user, normalizedRole, "Cross-tenant audit access attempt");
                throw new AccessDeniedException("Access denied");
            }
        }

        return new AccessContext(normalizedRole, scopedTenantId);
    }

    private User resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return null;
        }

        String principal = authentication.getName().trim();
        Optional<User> byEmail = userRepository.findByEmail(principal);
        if (byEmail.isPresent()) {
            return byEmail.get();
        }

        return userRepository.findByUsername(principal).orElse(null);
    }

    private Long resolveTenantId(User user) {
        Long tenantFromContext = TenantContext.getCurrentTenant();
        if (tenantFromContext != null) {
            return tenantFromContext;
        }
        if (user != null && user.getTenant() != null) {
            return (long) user.getTenant().getId();
        }
        return null;
    }

    private void auditDenied(String endpoint, User user, String role, String reason) {
        String userId = user != null && user.getId() != null ? String.valueOf(user.getId()) : "N/A";
        String detail = "userId=" + userId + ", role=" + role + ", endpoint=" + endpoint + ", reason=" + reason;
        auditLogService.denied("ACCESS_DENIED_EVENT", endpoint, detail);
    }

    private Instant parseInstantFilter(String value, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim();
        try {
            return Instant.parse(normalized);
        } catch (Exception ignored) {
            // Supports frontend date-only filters without requiring timezone input.
        }

        try {
            LocalDate localDate = LocalDate.parse(normalized);
            if (endOfDay) {
                return localDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).minusNanos(1).toInstant();
            }
            return localDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid date filter. Use ISO-8601 instant or yyyy-MM-dd");
        }
    }

    private String normalizeFilter(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }

    private Instant withDefault(Instant value, Instant fallback) {
        return value != null ? value : fallback;
    }

    private long normalizeTenantScope(Long tenantId) {
        return tenantId != null ? tenantId : TENANT_GLOBAL_SCOPE;
    }

    private Map<String, Object> toAuditPayload(AuditLog row, Map<Long, String> roleByUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("audit_id", row.getId());
        payload.put("actor_user_id", row.getUserId());
        payload.put("actor_role", resolveActorRole(row, roleByUserId));
        payload.put("action_type", row.getAction());
        payload.put("resource_type", resolveResourceType(row));
        payload.put("resource_id", row.getResourceId());
        payload.put("tenant_id", row.getTenantId());
        payload.put("event_timestamp", row.getOccurredAt());
        payload.put("outcome", row.getOutcome());
        payload.put("ip_address", row.getIpAddress());
        payload.put("device_context", row.getUserAgent());
        payload.put("metadata", buildMetadata(row));
        return payload;
    }

    private String resolveActorRole(AuditLog row, Map<Long, String> roleByUserId) {
        if (row.getActorRole() != null && !row.getActorRole().isBlank()) {
            return row.getActorRole();
        }
        Long userId = row.getUserId();
        if (userId == null) {
            return "unknown";
        }
        return roleByUserId.getOrDefault(userId, "unknown");
    }

    private String resolveResourceType(AuditLog row) {
        if (row.getResourceType() != null && !row.getResourceType().isBlank()) {
            return row.getResourceType();
        }
        return deriveResourceType(row.getRequestPath());
    }

    private Map<String, Object> buildMetadata(AuditLog row) {
        if (row.getMetadata() != null && !row.getMetadata().isBlank()) {
            return Map.of("raw", row.getMetadata());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("request_id", row.getRequestId());
        metadata.put("request_path", row.getRequestPath());
        metadata.put("http_method", row.getHttpMethod());
        metadata.put("detail", row.getDetail());
        return metadata;
    }

    private String deriveResourceType(String requestPath) {
        if (requestPath == null || requestPath.isBlank()) {
            return "system";
        }

        String normalized = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        String[] parts = normalized.split("/");
        if (parts.length < 2) {
            return normalized.toLowerCase(Locale.ROOT);
        }

        if ("api".equalsIgnoreCase(parts[0]) && parts.length >= 2) {
            return parts[1].toLowerCase(Locale.ROOT);
        }

        return parts[0].toLowerCase(Locale.ROOT);
    }

    private Map<Long, String> resolveRoles(List<AuditLog> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<Long> userIds = new HashSet<>();
        for (AuditLog row : rows) {
            if (row.getUserId() != null) {
                userIds.add(row.getUserId());
            }
        }

        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, String> roleByUserId = new HashMap<>();
        for (User user : userRepository.findAllById(userIds)) {
            roleByUserId.put(user.getId(), normalizeRole(user.getRole()));
        }
        return roleByUserId;
    }

    private Collection<String> accessEventActions() {
        return Set.of("login", "logout", "token_refresh", "mfa_challenge", "access_denied_event");
    }

    private int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.min(Math.max(value, min), max);
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "unknown";
        }

        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "retailer", "shop_owner", "owner", "admin", "store_admin" -> "store_admin";
            case "super_admin", "org_admin", "system_administrator" -> "system_administrator";
            case "audit_related" -> "audit_related";
            default -> normalized;
        };
    }

    private String csvValue(Object value) {
        if (value == null) {
            return "";
        }
        String raw = String.valueOf(value);
        String escaped = raw.replace("\"", "\"\"");
        return '"' + escaped + '"';
    }

    private ResponseEntity<Map<String, Object>> secureNoStoreResponse(ResponseEntity<Map<String, Object>> response) {
        return ResponseEntity.status(response.getStatusCode())
                .cacheControl(CacheControl.noStore().mustRevalidate().cachePrivate().sMaxAge(0, TimeUnit.SECONDS))
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(response.getBody());
    }

    private <T> ResponseEntity<T> secureNoStoreResponse(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate().cachePrivate().sMaxAge(0, TimeUnit.SECONDS))
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(body);
    }

    private static final class AccessContext {
        private final String normalizedRole;
        private final Long effectiveTenantId;

        private AccessContext(String normalizedRole, Long effectiveTenantId) {
            this.normalizedRole = normalizedRole;
            this.effectiveTenantId = effectiveTenantId;
        }
    }
}
