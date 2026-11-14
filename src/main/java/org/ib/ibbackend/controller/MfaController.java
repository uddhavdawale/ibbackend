package org.ib.ibbackend.controller;

import com.google.firebase.auth.FirebaseAuthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.ib.ibbackend.dto.MfaChallengeRequest;
import org.ib.ibbackend.dto.MfaOtpRequest;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.AuthChallengeService;
import org.ib.ibbackend.service.FirebaseTokenVerifierService;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.MailMfaService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
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
public class MfaController {
    private static final String PREAUTH_COOKIE = "mfaPreAuth";
    private static final Logger log = LoggerFactory.getLogger(MfaController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MailMfaService mailMfaService;

    @Autowired
    private AuthChallengeService authChallengeService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired(required = false)
    private FirebaseTokenVerifierService firebaseTokenVerifierService;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:5175,http://localhost:3000,http://localhost:8080,https://ibfrontend.vercel.app}")
    private String allowedOrigins;

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
    public ResponseEntity<Map<String, Object>> sendOtp(
            @Valid @RequestBody(required = false) MfaChallengeRequest request,
            @CookieValue(name = PREAUTH_COOKIE, required = false) String preAuthCookie,
            HttpServletRequest httpRequest) {
        if (isFirebaseMfaEnabled() && (request == null || request.getChallengeId() == null)) {
            log.debug("Firebase MFA code delivery delegated to the client SDK");
            return noStore(HttpStatus.ACCEPTED, Map.of(
                    "success", true,
                    "provider", "FIREBASE",
                    "message", "Use the Firebase verification flow."
            ));
        }
        if (!hasTrustedOrigin(httpRequest)) {
            log.warn("MFA code send rejected: untrusted origin");
            return noStore(HttpStatus.FORBIDDEN,
                    Map.of("success", false, "message", "MFA verification is unavailable."));
        }
        if (request == null || request.getChallengeId() == null || request.getChallengeId().isBlank()) {
            log.warn("MFA code send rejected: missing challenge ID");
            return noStore(HttpStatus.BAD_REQUEST,
                    Map.of("success", false, "message", "A valid MFA challenge is required."));
        }
        if (!mailMfaService.isReady()) {
            log.warn("MFA code send unavailable: mail delivery is not configured");
            auditFailure("MFA_CHALLENGE", "N/A", "Mail MFA is disabled");
            return noStore(HttpStatus.FORBIDDEN,
                    Map.of("success", false, "message", "MFA verification is unavailable."));
        }

        try {
            AuthChallengeService.PendingOtp pending =
                    authChallengeService.prepareOtp(request.getChallengeId(), preAuthCookie);
            mailMfaService.sendOtp(pending.email(), pending.otp());
            log.info("MFA email code queued");
            auditSuccess("MFA_CODE_SENT", "N/A", "Mail MFA code queued");
            return noStore(HttpStatus.ACCEPTED, Map.of(
                    "success", true,
                    "provider", "MAIL",
                    "message", "If this sign-in is eligible, a verification code will arrive shortly.",
                    "maskedDestination", pending.maskedDestination()
            ));
        } catch (AuthChallengeService.ResendLimitException ex) {
            log.debug("MFA code send throttled");
            return noStore(HttpStatus.TOO_MANY_REQUESTS,
                    Map.of("success", false, "message", "Please wait before requesting another code."));
        } catch (AuthChallengeService.ChallengeLimitException ex) {
            log.debug("MFA code send rejected: challenge invalid or expired");
            auditFailure("MFA_CODE_SENT", "N/A", "Invalid MFA challenge");
            return noStore(HttpStatus.UNAUTHORIZED,
                    Map.of("success", false, "message", "MFA challenge is invalid or expired."));
        } catch (MailMfaService.MailQueueFullException | IllegalStateException ex) {
            authChallengeService.invalidateOtp(request.getChallengeId(), preAuthCookie);
            log.warn("MFA email code could not be queued");
            auditFailure("MFA_CODE_SENT", "N/A", "MFA email could not be queued");
            return noStore(HttpStatus.SERVICE_UNAVAILABLE,
                    Map.of("success", false, "message", "MFA verification is temporarily unavailable."));
        }
    }

    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancelChallenge(
            @RequestBody(required = false) MfaChallengeRequest request,
            @CookieValue(name = PREAUTH_COOKIE, required = false) String preAuthCookie,
            HttpServletRequest httpRequest) {
        if (!hasTrustedOrigin(httpRequest)) {
            log.warn("MFA cancellation rejected: untrusted origin");
            return noStore(HttpStatus.FORBIDDEN,
                    Map.of("success", false, "message", "MFA verification is unavailable."));
        }
        if (request == null || request.getChallengeId() == null || request.getChallengeId().isBlank()) {
            log.warn("MFA cancellation rejected: missing challenge ID");
            return noStore(HttpStatus.BAD_REQUEST,
                    Map.of("success", false, "message", "A valid MFA challenge is required."));
        }

        try {
            authChallengeService.cancelChallenge(request.getChallengeId(), preAuthCookie);
            log.info("MFA challenge cancelled");
            ResponseCookie clearPreAuthCookie = clearPreAuthCookie();
            auditSuccess("MFA_CANCEL", "N/A", "Mail MFA challenge cancelled");
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE, clearPreAuthCookie.toString())
                    .body(Map.of("success", true, "message", "MFA challenge cancelled."));
        } catch (AuthChallengeService.ChallengeLimitException ex) {
            log.debug("MFA cancellation rejected: challenge invalid or expired");
            auditFailure("MFA_CANCEL", "N/A", "Invalid MFA challenge");
            return noStore(HttpStatus.UNAUTHORIZED,
                    Map.of("success", false, "message", "MFA challenge is invalid or expired."));
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyOtp(
            @Valid @RequestBody MfaOtpRequest request,
            @CookieValue(name = PREAUTH_COOKIE, required = false) String preAuthCookie,
            HttpServletRequest httpRequest) {
        String firebaseIdToken = request.getFirebaseIdToken();
        if (firebaseIdToken != null && !firebaseIdToken.isBlank() && isFirebaseMfaEnabled()) {
            Optional<User> userOpt = findUserByLoginIdentifier(request.getUsername());
            if (userOpt.isEmpty()) {
                log.debug("Firebase MFA verification rejected: account not found");
                auditFailure("MFA_VERIFY", "N/A", "Firebase MFA account not found");
                return noStore(HttpStatus.UNAUTHORIZED,
                        Map.of("success", false, "message", "MFA verification failed."));
            }
            User user = userOpt.get();
            if (user.isDisabled() || UserRolePolicy.isDisabledRole(user.getRole())) {
                log.warn("Firebase MFA verification rejected: account is disabled");
                refreshTokenService.revokeAllActiveTokensForUser(user);
                auditFailure("MFA_VERIFY", String.valueOf(user.getId()), "Disabled account tried MFA verification");
                return noStore(HttpStatus.UNAUTHORIZED,
                        Map.of("success", false, "message", "MFA verification failed."));
            }
            try {
                FirebaseTokenVerifierService.FirebaseIdentity identity =
                        firebaseTokenVerifierService.verifyIdToken(firebaseIdToken);
                if (!matchesUserIdentity(user, identity)) {
                    log.warn("Firebase MFA verification rejected: identity mismatch");
                    auditFailure("MFA_VERIFY", String.valueOf(user.getId()), "Firebase identity mismatch");
                    return noStore(HttpStatus.UNAUTHORIZED,
                            Map.of("success", false, "message", "MFA verification failed."));
                }
                log.info("Firebase MFA verification succeeded");
                auditSuccess("MFA_VERIFY", String.valueOf(user.getId()), "Firebase MFA verification successful");
                return issueLoginSuccessResponse(user);
            } catch (FirebaseAuthException | IllegalArgumentException ex) {
                log.debug("Firebase MFA verification rejected: token invalid");
                auditFailure("MFA_VERIFY", String.valueOf(user.getId()), "Invalid Firebase MFA token");
                return noStore(HttpStatus.UNAUTHORIZED,
                        Map.of("success", false, "message", "MFA verification failed."));
            }
        }

        if (!hasTrustedOrigin(httpRequest)) {
            log.warn("MFA verification rejected: untrusted origin");
            return noStore(HttpStatus.FORBIDDEN,
                    Map.of("success", false, "message", "MFA verification is unavailable."));
        }
        if (!mailMfaService.isEnabled()) {
            log.warn("MFA verification unavailable: email MFA is disabled");
            auditFailure("MFA_VERIFY", "N/A", "No mail OTP verifier enabled");
            return noStore(HttpStatus.FORBIDDEN,
                    Map.of("success", false, "message", "MFA verification is unavailable."));
        }
        if (request.getChallengeId() == null || request.getChallengeId().isBlank()) {
            log.warn("MFA verification rejected: missing challenge ID");
            return noStore(HttpStatus.BAD_REQUEST,
                    Map.of("success", false, "message", "A valid MFA challenge is required."));
        }

        Optional<User> userOpt = authChallengeService.verifyOtp(
                request.getChallengeId(), preAuthCookie, request.getOtp());
        if (userOpt.isEmpty()) {
            log.debug("MFA verification rejected: code invalid, expired, or challenge unavailable");
            auditFailure("MFA_VERIFY", "N/A", "Invalid or expired mail OTP");
            return noStore(HttpStatus.UNAUTHORIZED,
                    Map.of("success", false, "message", "MFA verification failed."));
        }

        User user = userOpt.get();
        if (user.isDisabled() || UserRolePolicy.isDisabledRole(user.getRole())) {
            log.warn("MFA verification rejected: account is disabled");
            refreshTokenService.revokeAllActiveTokensForUser(user);
            auditFailure("MFA_VERIFY", String.valueOf(user.getId()), "Disabled account tried MFA verification");
            return noStore(HttpStatus.UNAUTHORIZED,
                    Map.of("success", false, "message", "MFA verification failed."));
        }
        log.info("MFA verification succeeded");
        auditSuccess("MFA_VERIFY", String.valueOf(user.getId()), "Mail MFA verification successful");
        return issueLoginSuccessResponse(user);
    }

    private ResponseEntity<Map<String, Object>> issueLoginSuccessResponse(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        refreshTokenService.storeToken(user, refreshToken);

        ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
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
        userPayload.put("businessName", user.getBusinessName());
        userPayload.put("tenantId", user.getTenant() != null ? user.getTenant().getId() : null);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Login successful");
        response.put("token", accessToken);
        response.put("accessToken", accessToken);
        response.put("tokenType", "Bearer");
        response.put("username", loginUsername);
        response.put("role", user.getRole());
        response.put("email", user.getEmail());
        response.put("user", userPayload);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .header(HttpHeaders.SET_COOKIE, clearPreAuthCookie().toString())
                .body(response);
    }

    private ResponseCookie clearPreAuthCookie() {
        return ResponseCookie.from(PREAUTH_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/api/mfa")
                .maxAge(Duration.ZERO)
                .build();
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
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private Optional<User> findUserByLoginIdentifier(String loginIdentifier) {
        if (loginIdentifier == null || loginIdentifier.isBlank()) {
            return Optional.empty();
        }
        String identifier = loginIdentifier.trim();
        Optional<User> userByUsername = userRepository.findByUsername(identifier.toLowerCase(Locale.ROOT));
        if (userByUsername != null && userByUsername.isPresent()) {
            return userByUsername;
        }
        return userRepository.findByEmail(identifier.toLowerCase(Locale.ROOT));
    }

    private String resolveLoginUsername(User user) {
        return user.getUsername() != null && !user.getUsername().isBlank()
                ? user.getUsername()
                : user.getEmail();
    }

    private ResponseEntity<Map<String, Object>> noStore(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private boolean hasTrustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return false;
        }
        for (String allowedOrigin : allowedOrigins.split(",")) {
            if (origin.equals(allowedOrigin.trim())) {
                return true;
            }
        }
        return false;
    }
}
