package org.ib.ibbackend.controller;

import jakarta.validation.Valid;
import org.ib.ibbackend.dto.PasswordResetCompleteRequest;
import org.ib.ibbackend.dto.PasswordResetRequest;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.MailMfaService;
import org.ib.ibbackend.service.PasswordResetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth/password")
public class PasswordRecoveryController {
    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryController.class);
    private static final String GENERIC_MESSAGE =
            "If an account matches that email, password reset instructions will arrive shortly.";

    private final PasswordResetService passwordResetService;
    private final MailMfaService mailMfaService;
    private final AuditLogService auditLogService;

    public PasswordRecoveryController(PasswordResetService passwordResetService,
                                      MailMfaService mailMfaService,
                                      AuditLogService auditLogService) {
        this.passwordResetService = passwordResetService;
        this.mailMfaService = mailMfaService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/forgot")
    public ResponseEntity<Map<String, String>> requestReset(@Valid @RequestBody PasswordResetRequest request) {
        log.info("Password reset request received");
        Optional<PasswordResetService.ResetEmail> resetEmail = passwordResetService.requestReset(request.getEmail());
        if (resetEmail.isPresent()) {
            PasswordResetService.ResetEmail email = resetEmail.get();
            try {
                mailMfaService.sendPasswordReset(email.email(), email.token());
                log.info("Password reset email queued");
                auditLogService.success("PASSWORD_RESET_REQUESTED", "N/A", "Password reset email queued");
            } catch (MailMfaService.MailQueueFullException | IllegalStateException ex) {
                passwordResetService.invalidateResetToken(email.token());
                log.warn("Password reset email could not be queued");
                auditLogService.failure("PASSWORD_RESET_REQUESTED", "N/A", "Password reset email could not be queued");
            }
        }

        log.debug("Password reset request processed with generic response");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("message", GENERIC_MESSAGE));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> completeReset(
            @Valid @RequestBody PasswordResetCompleteRequest request) {
        Optional<User> user;
        try {
            user = passwordResetService.resetPassword(request.getToken(), request.getNewPassword());
        } catch (IllegalArgumentException ex) {
            log.debug("Password reset rejected: password failed policy validation");
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("message", "The password reset request is invalid or has expired."));
        }
        if (user.isEmpty()) {
            log.debug("Password reset rejected: token invalid, expired, or already used");
            auditLogService.failure("PASSWORD_RESET_COMPLETED", "N/A", "Invalid or expired reset token");
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("message", "The password reset request is invalid or has expired."));
        }

        User updatedUser = user.get();
        log.info("Password reset completed and existing sessions revoked");
        auditLogService.success("PASSWORD_RESET_COMPLETED", String.valueOf(updatedUser.getId()),
                "Password updated and existing sessions revoked");
        try {
            mailMfaService.sendPasswordChangedNotification(updatedUser.getEmail());
        } catch (MailMfaService.MailQueueFullException | IllegalStateException ex) {
            log.warn("Password change notification could not be queued");
            auditLogService.failure("PASSWORD_CHANGE_NOTIFICATION", String.valueOf(updatedUser.getId()),
                    "Password change notification could not be queued");
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
