package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.LoginModel;
import org.ib.ibbackend.dto.LoginRequest;
import org.ib.ibbackend.dto.LoginResponse;
import org.ib.ibbackend.dto.MfaLoginChallengeResponse;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.AuthChallengeService;
import org.ib.ibbackend.service.FirebaseTokenVerifierService;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.LoginCryptoService;
import org.ib.ibbackend.service.MailMfaService;
import org.ib.ibbackend.service.PasswordSecurityService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.ib.ibbackend.service.AuthCookieService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController()
public class LoginController {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PasswordSecurityService passwordSecurityService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private AuthCookieService authCookieService = AuthCookieService.defaultFactory();

    @Autowired
    private MailMfaService mailMfaService;

    @Autowired(required = false)
    private AuthChallengeService authChallengeService;

    @Autowired(required = false)
    private FirebaseTokenVerifierService firebaseTokenVerifierService;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    @Autowired(required = false)
    private LoginCryptoService loginCryptoService;

    @Value("${app.security.login.encrypted-only:false}")
    private boolean encryptedLoginOnly;

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

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

    @PostMapping(path = {"/api/login", "/login", "/api/auth/login"})
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginRequest normalizedRequest;
        try {
            normalizedRequest = normalizeLoginRequest(request);
        } catch (LoginCryptoService.InvalidLoginCryptoPayloadException ex) {
            log.warn("Login crypto validation/decryption failed on /api/login: {}", ex.getMessage());
            auditFailure("LOGIN_CRYPTO", "N/A", "Encrypted login payload rejected");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginResponse(false, "Invalid username or password"));
        }

        Optional<User> userOpt = findUserByLoginIdentifier(resolveLoginIdentifier(normalizedRequest.getUsername(), normalizedRequest.getEmail()));

        if (userOpt.isEmpty()) {
            auditFailure("LOGIN", "N/A", "Unknown username");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginResponse(false, "Invalid username or password"));
        }

        User user = userOpt.get();
        if (user.isDisabled() || UserRolePolicy.isDisabledRole(user.getRole())) {
            refreshTokenService.revokeAllActiveTokensForUser(user);
            auditFailure("LOGIN", String.valueOf(user.getId()), "Disabled account login attempt");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new LoginResponse(false, "Your account is disabled. Contact store admin."));
        }

        if (!passwordSecurityService.matches(normalizedRequest.getPassword(), user.getPasswordHash())) {
            auditFailure("LOGIN", String.valueOf(user.getId()), "Invalid password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginResponse(false, "Invalid username or password"));
        }

        boolean firebaseMfaEnabled = isFirebaseMfaEnabled();
        boolean mailMfaEnabled = mailMfaService != null && mailMfaService.isEnabled();

        if (firebaseMfaEnabled || mailMfaEnabled) {
            if (firebaseMfaEnabled) {
                log.info("Firebase MFA challenge issued");
                auditSuccess("MFA_CHALLENGE", String.valueOf(user.getId()), "Firebase MFA challenge required");
                LoginResponse response = new LoginResponse(false, "MFA verification required");
                String loginUsername = resolveLoginUsername(user);
                response.setRequiresMfa(true);
                response.setUsername(loginUsername);
                response.setRole(user.getRole());
                response.setEmail(user.getEmail());
                response.setUser(Map.of(
                        "id", user.getId(),
                    "username", loginUsername,
                        "name", user.getOwnerName(),
                        "email", user.getEmail(),
                        "role", user.getRole(),
                        "mfaProvider", "FIREBASE"
                ));
                return ResponseEntity.ok(response);
            }

            if (!mailMfaService.isReady()) {
                log.warn("MFA challenge unavailable: mail delivery is not configured");
                auditFailure("MFA_CHALLENGE", String.valueOf(user.getId()), "Mail delivery is not configured");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new LoginResponse(false, "MFA verification is temporarily unavailable."));
            }

            try {
                AuthChallengeService.IssuedChallenge challenge = authChallengeService.createChallenge(user);
                Duration cookieLifetime = Duration.between(
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC), challenge.expiresAt());
                ResponseCookie preAuthCookie = ResponseCookie.from("mfaPreAuth", challenge.cookieValue())
                        .httpOnly(true)
                        .secure(true)
                        .sameSite("None")
                        .path("/api/mfa")
                        .maxAge(cookieLifetime.isNegative() ? Duration.ZERO : cookieLifetime)
                        .build();
                log.info("Email MFA challenge issued");
                auditSuccess("MFA_CHALLENGE", String.valueOf(user.getId()), "Mail MFA challenge issued");
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.SET_COOKIE, preAuthCookie.toString())
                        .body(new MfaLoginChallengeResponse(Map.of(
                                "challengeId", challenge.challengeId(),
                                "maskedDestination", challenge.maskedDestination(),
                                "expiresAt", challenge.expiresAt().atOffset(java.time.ZoneOffset.UTC).toInstant().toString()
                        )));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                log.warn("Email MFA challenge could not be created");
                auditFailure("MFA_CHALLENGE", String.valueOf(user.getId()), "MFA challenge could not be created");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new LoginResponse(false, "MFA verification is temporarily unavailable."));
            }
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        refreshTokenService.storeToken(user, refreshToken);

        ResponseCookie refreshCookie = authCookieService.refreshCookie(
                refreshToken, jwtService.getRefreshTokenExpiryMs());

        Map<String, Object> userPayload = new HashMap<>();
        String loginUsername = resolveLoginUsername(user);
        userPayload.put("id", user.getId());
        userPayload.put("username", loginUsername);
        userPayload.put("name", user.getOwnerName());
        userPayload.put("email", user.getEmail());
        userPayload.put("role", user.getRole());
        userPayload.put("businessName", user.getBusinessName());
        userPayload.put("tenantId", user.getTenant() != null ? user.getTenant().getId() : null);

        LoginResponse response = new LoginResponse(true, "Login successful");
        response.setToken(accessToken);
        response.setAccessToken(accessToken);
        response.setTokenType("Bearer");
        response.setUsername(loginUsername);
        response.setRole(user.getRole());
        response.setEmail(user.getEmail());
        response.setUser(userPayload);

        auditSuccess("LOGIN", String.valueOf(user.getId()), "Login successful");

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .header(HttpHeaders.SET_COOKIE, authCookieService.clearLegacyRefreshCookie().toString())
                .body(response);
    }

    private Optional<User> findUserByLoginIdentifier(String loginIdentifier) {
        if (loginIdentifier == null || loginIdentifier.isBlank()) {
            return Optional.empty();
        }

        String trimmedIdentifier = loginIdentifier.trim();
        String normalizedUsername = trimmedIdentifier.toLowerCase(Locale.ROOT);

        Optional<User> userByUsername = userRepository.findByUsername(normalizedUsername);
        if (userByUsername != null && userByUsername.isPresent()) {
            return ensureTenant(userByUsername.get());
        }

        Optional<User> userByEmail = userRepository.findByEmail(trimmedIdentifier);
        if (!userByEmail.isPresent()) {
            userByEmail = userRepository.findByEmail(trimmedIdentifier.toLowerCase(Locale.ROOT));
        }
        if (userByEmail.isPresent()) {
            return ensureTenant(userByEmail.get());
        }

        return Optional.empty();
    }

    private String resolveLoginIdentifier(String username, String email) {
        if (username != null && !username.isBlank()) {
            return username;
        }
        if (email != null && !email.isBlank()) {
            return email;
        }
        return null;
    }

    private String resolveLoginUsername(User user) {
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return user.getEmail();
    }

    private Optional<User> ensureTenant(User user) {
        if (user == null) {
            return Optional.empty();
        }
        if (user.getTenant() == null && !UserRolePolicy.isSystemAdmin(user.getRole())) {
            user.setTenant(createUniqueTenantForUser(user.getBusinessName(), user.getEmail()));
            userRepository.save(user);
        }
        return Optional.of(user);
    }

    private Tenant createUniqueTenantForUser(String businessName, String email) {
        String normalizedBusinessName = businessName == null ? "Default Business" : businessName.trim();
        if (normalizedBusinessName.isBlank()) {
            normalizedBusinessName = "Default Business";
        }

        Tenant tenant = new Tenant();
        tenant.setName(normalizedBusinessName);
        tenant.setCode(buildUniqueTenantCode(normalizedBusinessName, email));
        return tenantRepository.save(tenant);
    }

    private String buildUniqueTenantCode(String businessName, String email) {
        String base = businessName == null ? "TEN" : businessName.replaceAll("[^A-Za-z0-9]", "");
        base = base == null || base.isBlank() ? "TEN" : base.toUpperCase();

        String identity = email == null ? String.valueOf(System.currentTimeMillis()) : email.trim();
        int hash = Math.abs((identity + "-" + businessName).hashCode()) % 9000;
        int suffix = 1000 + hash;

        String prefix = base.length() > 4 ? base.substring(0, 4) : base;
        if (prefix.length() < 4) {
            prefix = (prefix + "TEN").substring(0, 4);
        }

        return prefix + String.valueOf(suffix).substring(Math.max(0, String.valueOf(suffix).length() - 4));
    }

    private boolean isFirebaseMfaEnabled() {
        return firebaseTokenVerifierService != null && firebaseTokenVerifierService.isEnabled();
    }

    @RequestMapping("/dummy")
    public String getDummyData() {
        return "This is some dummy data.";
    }

    @GetMapping("/getLoginData")
    public LoginModel getLoginData() {
        LoginModel loginModel = new LoginModel();
        loginModel.setUsername("admin@gmail.com");
        loginModel.setPassword("password");
        return loginModel;
    }

    private LoginRequest normalizeLoginRequest(LoginRequest request) {
        if (loginCryptoService == null) {
            if (encryptedLoginOnly) {
                throw new LoginCryptoService.InvalidLoginCryptoPayloadException("Encrypted login payload is required");
            }
            return request;
        }

        boolean encrypted = loginCryptoService.isEncryptedPayload(request);
        if (encryptedLoginOnly && !encrypted) {
            throw new LoginCryptoService.InvalidLoginCryptoPayloadException("Encrypted login payload is required");
        }

        LoginRequest normalized = loginCryptoService.resolveLoginRequest(request);
        if (encrypted) {
            auditSuccess("LOGIN_CRYPTO", "N/A", "Encrypted login payload decrypted");
        }
        return normalized;
    }
}
