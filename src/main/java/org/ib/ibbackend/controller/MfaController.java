package org.ib.ibbackend.controller;

import com.google.firebase.auth.FirebaseAuthException;
import org.ib.ibbackend.dto.MfaOtpRequest;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.FirebaseTokenVerifierService;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.MailMfaService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/mfa")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true")
public class MfaController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MailMfaService mailMfaService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired(required = false)
    private FirebaseTokenVerifierService firebaseTokenVerifierService;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    private void auditSuccess(String action, String resourceId, String detail) {
        if (auditLogService != null) {
            auditLogService.success(action, resourceId, detail);
        }
    }

    private void auditFailure(String action, String resourceId, String detail) {
        if (auditLogService != null) {
            auditLogService.failure(action, resourceId, detail);
        }
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendOtp(@RequestBody Map<String, String> request) {
        boolean firebaseEnabled = isFirebaseMfaEnabled();
        if (firebaseEnabled) {
            auditSuccess("MFA_CHALLENGE", "N/A", "Firebase MFA enabled; send endpoint acknowledged");
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("provider", "FIREBASE");
            response.put("message", "Firebase MFA is enabled. Use the Firebase verification flow.");
            return ResponseEntity.ok(response);
        }

        if (!mailMfaService.isEnabled()) {
            auditFailure("MFA_CHALLENGE", "N/A", "Mail MFA is disabled");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "Mail MFA is disabled in configuration"));
        }

        String username = request.get("username");
        Optional<User> userOpt = findUserByLoginIdentifier(username);
        if (userOpt.isEmpty()) {
            auditFailure("MFA_CHALLENGE", "N/A", "User not found for MFA send");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "User not found"));
        }

        User user = userOpt.get();
        mailMfaService.issueOtp(user.getEmail());
        auditSuccess("MFA_CHALLENGE", String.valueOf(user.getId()), "Mail OTP sent");

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("provider", "MAIL");
        response.put("message", "OTP sent to your email");
        response.put("email", user.getEmail());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyOtp(@RequestBody MfaOtpRequest request) {
        Optional<User> userOpt = findUserByLoginIdentifier(request.getUsername());
        if (userOpt.isEmpty()) {
            auditFailure("MFA_VERIFY", "N/A", "User not found for MFA verify");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "User not found"));
        }

        User user = userOpt.get();

        String firebaseIdToken = request.getFirebaseIdToken();
        if (firebaseIdToken != null && !firebaseIdToken.isBlank() && isFirebaseMfaEnabled()) {
            try {
                FirebaseTokenVerifierService.FirebaseIdentity identity = firebaseTokenVerifierService.verifyIdToken(firebaseIdToken);
                if (!matchesUserIdentity(user, identity)) {
                    auditFailure("MFA_VERIFY", String.valueOf(user.getId()), "Firebase identity mismatch");
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(Map.of("success", false, "message", "Firebase identity mismatch"));
                }
                auditSuccess("MFA_VERIFY", String.valueOf(user.getId()), "Firebase MFA verification successful");
                return issueLoginSuccessResponse(user);
            } catch (FirebaseAuthException | IllegalArgumentException ex) {
                auditFailure("MFA_VERIFY", String.valueOf(user.getId()), "Invalid Firebase OTP token");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "message", "Invalid Firebase OTP token"));
            }
        }

        if (!mailMfaService.isEnabled()) {
            auditFailure("MFA_VERIFY", String.valueOf(user.getId()), "No OTP verifier enabled");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "No OTP verifier enabled. Enable Firebase or Mail MFA"));
        }

        if (!mailMfaService.verifyOtp(user.getEmail(), request.getOtp())) {
            auditFailure("MFA_VERIFY", String.valueOf(user.getId()), "Invalid or expired mail OTP");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Invalid or expired OTP"));
        }

        auditSuccess("MFA_VERIFY", String.valueOf(user.getId()), "Mail OTP verification successful");
        return issueLoginSuccessResponse(user);
    }

    private ResponseEntity<Map<String, Object>> issueLoginSuccessResponse(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        refreshTokenService.storeToken(user, refreshToken);

        ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(7))
                .build();

        Map<String, Object> userPayload = new HashMap<>();
        String loginUsername = resolveLoginUsername(user);
        userPayload.put("id", user.getId());
        userPayload.put("username", loginUsername);
        userPayload.put("name", user.getOwnerName());
        userPayload.put("email", user.getEmail());
        userPayload.put("role", user.getRole());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Login successful");
        response.put("accessToken", accessToken);
        response.put("refreshToken", refreshToken);
        response.put("tokenType", "Bearer");
        response.put("user", userPayload);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(response);
    }

    private boolean isFirebaseMfaEnabled() {
        return firebaseTokenVerifierService != null && firebaseTokenVerifierService.isEnabled();
    }

    private boolean matchesUserIdentity(User user, FirebaseTokenVerifierService.FirebaseIdentity identity) {
        if (user == null || identity == null) {
            return false;
        }

        String firebaseEmail = normalize(identity.email());
        String userEmail = normalize(user.getEmail());
        if (firebaseEmail != null && userEmail != null && firebaseEmail.equals(userEmail)) {
            return true;
        }

        String firebasePhone = normalize(identity.phoneNumber());
        String userPhone = normalize(user.getPhone());
        return firebasePhone != null && userPhone != null && firebasePhone.equals(userPhone);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.isBlank() ? null : normalized;
    }

    private Optional<User> findUserByLoginIdentifier(String loginIdentifier) {
        if (loginIdentifier == null || loginIdentifier.isBlank()) {
            return Optional.empty();
        }

        String trimmedIdentifier = loginIdentifier.trim();
        String normalizedUsername = trimmedIdentifier.toLowerCase(Locale.ROOT);

        Optional<User> userByUsername = userRepository.findByUsername(normalizedUsername);
        if (userByUsername != null && userByUsername.isPresent()) {
            return userByUsername;
        }

        Optional<User> userByEmail = userRepository.findByEmail(trimmedIdentifier);
        if (!userByEmail.isPresent()) {
            userByEmail = userRepository.findByEmail(trimmedIdentifier.toLowerCase(Locale.ROOT));
        }
        if (userByEmail.isPresent()) {
            return userByEmail;
        }

        return Optional.empty();
    }

    private String resolveLoginUsername(User user) {
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return user.getEmail();
    }
}
