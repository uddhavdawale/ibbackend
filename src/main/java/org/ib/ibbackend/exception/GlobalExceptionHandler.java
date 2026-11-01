package org.ib.ibbackend.exception;

import org.ib.ibbackend.service.AuditLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final AuditLogService auditLogService;

    public GlobalExceptionHandler(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        if (auditLogService != null) {
            auditLogService.denied("ACCESS_DENIED_EVENT", "N/A", ex.getMessage());
        }
        return ResponseEntity.status(403).body(Map.of(
                "success", false,
                "message", "Access denied"
        ));
    }
}
