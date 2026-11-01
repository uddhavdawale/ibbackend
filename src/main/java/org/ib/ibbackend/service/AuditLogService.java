package org.ib.ibbackend.service;

import jakarta.servlet.http.HttpServletRequest;
import org.ib.ibbackend.entity.AuditLog;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.AuditLogRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Optional;

@Service
public class AuditLogService {
    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    public static final String REQUEST_ID_ATTRIBUTE = "requestId";

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditLogService(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    public void success(String action, String resourceId, String detail) {
        write(action, resourceId, "SUCCESS", detail, null, null);
    }

    public void failure(String action, String resourceId, String detail) {
        write(action, resourceId, "FAILURE", detail, null, null);
    }

    public void denied(String action, String resourceId, String detail) {
        write(action, resourceId, "DENIED", detail, null, null);
    }

    public void write(String action,
                      String resourceId,
                      String outcome,
                      String detail,
                      Long explicitUserId,
                      Long explicitTenantId) {
        try {
            HttpServletRequest request = currentRequest();
            AuditLog entry = new AuditLog();
            entry.setOccurredAt(Instant.now());
            entry.setAction(safe(action, 100, "UNKNOWN_ACTION"));
            entry.setResourceType(safe(resolveResourceType(request), 100, "system"));
            entry.setResourceId(safe(resourceId, 255, null));
            entry.setOutcome(safe(outcome, 30, "UNKNOWN"));
            entry.setDetail(safe(detail, 1000, null));

            if (request != null) {
                entry.setRequestPath(safe(request.getRequestURI(), 255, null));
                entry.setHttpMethod(safe(request.getMethod(), 10, null));
                entry.setUserAgent(safe(request.getHeader("User-Agent"), 512, null));
                entry.setIpAddress(safe(resolveIpAddress(request), 64, null));
                Object requestId = request.getAttribute(REQUEST_ID_ATTRIBUTE);
                if (requestId != null) {
                    entry.setRequestId(safe(String.valueOf(requestId), 100, null));
                }
            }

            Long resolvedUserId = explicitUserId != null ? explicitUserId : resolveCurrentUserId();
            Long resolvedTenantId = explicitTenantId != null ? explicitTenantId : resolveCurrentTenantId();
            entry.setUserId(resolvedUserId);
            entry.setTenantId(resolvedTenantId);
            entry.setActorRole(safe(resolveCurrentUserRole(), 80, "unknown"));
            entry.setMetadata(buildMetadataJson(entry));

            auditLogRepository.save(entry);
        } catch (Exception ex) {
            log.warn("Audit log persistence failed for action={}", action, ex);
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private Long resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }

        String principal = auth.getName();
        if (principal == null || principal.isBlank()) {
            return null;
        }

        Optional<User> user = userRepository.findByEmail(principal);
        return user.map(User::getId).orElse(null);
    }

    private String resolveCurrentUserRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }

        String principal = auth.getName();
        if (principal == null || principal.isBlank()) {
            return null;
        }

        return userRepository.findByEmail(principal)
                .map(User::getRole)
                .orElse(null);
    }

    private Long resolveCurrentTenantId() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId != null) {
            return tenantId;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }

        String principal = auth.getName();
        if (principal == null || principal.isBlank()) {
            return null;
        }

        return userRepository.findByEmail(principal)
                .map(User::getTenant)
                .map(tenant -> tenant != null ? Long.valueOf(tenant.getId()) : null)
                .orElse(null);
    }

    private String resolveIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String[] segments = xForwardedFor.split(",");
            if (segments.length > 0 && !segments[0].isBlank()) {
                return segments[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String resolveResourceType(HttpServletRequest request) {
        if (request == null || request.getRequestURI() == null || request.getRequestURI().isBlank()) {
            return "system";
        }

        String normalized = request.getRequestURI().startsWith("/")
                ? request.getRequestURI().substring(1)
                : request.getRequestURI();
        String[] parts = normalized.split("/");
        if (parts.length == 0 || parts[0].isBlank()) {
            return "system";
        }
        if (parts.length > 1 && "api".equalsIgnoreCase(parts[0])) {
            return parts[1].toLowerCase();
        }
        return parts[0].toLowerCase();
    }

    private String safe(String value, int maxLen, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return fallback;
        }
        return normalized.length() > maxLen ? normalized.substring(0, maxLen) : normalized;
    }

    private String buildMetadataJson(AuditLog entry) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        appendJsonField(json, "requestId", entry.getRequestId(), true);
        appendJsonField(json, "requestPath", entry.getRequestPath(), false);
        appendJsonField(json, "httpMethod", entry.getHttpMethod(), false);
        appendJsonField(json, "detail", entry.getDetail(), false);
        json.append('}');

        String metadata = json.toString();
        return metadata.length() > 4000 ? metadata.substring(0, 4000) : metadata;
    }

    private void appendJsonField(StringBuilder sb, String field, String value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(escapeJson(field)).append('"').append(':');
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escapeJson(value)).append('"');
        }
    }

    private String escapeJson(String raw) {
        String escaped = raw.replace("\\", "\\\\");
        escaped = escaped.replace("\"", "\\\"");
        escaped = escaped.replace("\n", "\\n");
        escaped = escaped.replace("\r", "\\r");
        return escaped;
    }
}
