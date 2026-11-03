package org.ib.ibbackend.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.ib.ibbackend.dto.*;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.LoginCryptoService;
import org.ib.ibbackend.service.PasswordSecurityService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.ib.ibbackend.service.UserProvisioningService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true")
public class AuthController {

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private TenantRepository tenantRepo;

    @Autowired
    private PasswordSecurityService passwordSecurityService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private UserProvisioningService userProvisioningService;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    @Autowired(required = false)
    private LoginCryptoService loginCryptoService;

    @Value("${app.security.login.encrypted-only:false}")
    private boolean encryptedLoginOnly;

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

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req) {
        LoginRequest normalizedRequest;
        try {
            normalizedRequest = normalizeLoginRequest(req);
        } catch (LoginCryptoService.InvalidLoginCryptoPayloadException ex) {
            auditFailure("LOGIN_CRYPTO", "N/A", "Encrypted login payload rejected");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<User> userOpt = findUserByLoginIdentifier(resolveLoginIdentifier(normalizedRequest.getUsername(), normalizedRequest.getEmail()));
        if (userOpt.isEmpty()) {
            auditFailure("LOGIN", "N/A", "Unknown username");
            return ResponseEntity.status(401).build();
        }

        User user = userOpt.get();
        if (user.isDisabled() || UserRolePolicy.isDisabledRole(user.getRole())) {
            refreshTokenService.revokeAllActiveTokensForUser(user);
            auditFailure("LOGIN", String.valueOf(user.getId()), "Disabled account login attempt");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (!passwordSecurityService.matches(normalizedRequest.getPassword(), user.getPasswordHash())) {
            auditFailure("LOGIN", String.valueOf(user.getId()), "Invalid password");
            return ResponseEntity.status(401).build();
        }

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
        userPayload.put("businessName", user.getBusinessName());
        userPayload.put("tenantId", user.getTenant() != null ? user.getTenant().getId() : null);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Login successful");
        response.put("accessToken", accessToken);
        response.put("refreshToken", refreshToken);
        response.put("tokenType", "Bearer");
        response.put("user", userPayload);

        auditSuccess("LOGIN", String.valueOf(user.getId()), "Login successful");

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody(required = false) RefreshTokenRequest request,
                                         @CookieValue(name = "refreshToken", required = false) String cookieRefreshToken,
                                         HttpServletRequest httpRequest) {
        String incomingRefreshToken = null;

        if (request != null && request.getRefreshToken() != null && !request.getRefreshToken().isBlank()) {
            incomingRefreshToken = request.getRefreshToken();
        } else if (cookieRefreshToken != null && !cookieRefreshToken.isBlank()) {
            incomingRefreshToken = cookieRefreshToken;
        }

        if (incomingRefreshToken == null || incomingRefreshToken.isBlank()) {
            auditFailure("TOKEN_REFRESH", "N/A", "Refresh token missing");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token missing");
        }

        final String refreshTokenToValidate = incomingRefreshToken;

        try {
            String requestedIdentifier = request == null
                ? null
                : resolveLoginIdentifier(request.getUsername(), request.getEmail());

            User user = findUserByLoginIdentifier(requestedIdentifier)
                    .or(() -> findUserByLoginIdentifier(jwtService.extractRefreshClaims(refreshTokenToValidate).getSubject()))
                    .orElseThrow(() -> new RuntimeException("User not found"));

            if (user.isDisabled() || UserRolePolicy.isDisabledRole(user.getRole())) {
                refreshTokenService.revokeAllActiveTokensForUser(user);
                auditFailure("TOKEN_REFRESH", String.valueOf(user.getId()), "Disabled account refresh attempt");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("User account is disabled");
            }

            if (!refreshTokenService.isValidForUser(user, refreshTokenToValidate)) {
                auditFailure("TOKEN_REFRESH", String.valueOf(user.getId()), "Invalid refresh token for user");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or expired refresh token");
            }

            String newAccessToken = jwtService.generateAccessToken(user);
            String newRefreshToken = jwtService.generateRefreshToken(user);
            refreshTokenService.rotateToken(user, refreshTokenToValidate, newRefreshToken);

            ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", newRefreshToken)
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
            userPayload.put("businessName", user.getBusinessName());
            userPayload.put("tenantId", user.getTenant() != null ? user.getTenant().getId() : null);

            RefreshTokenResponse response = new RefreshTokenResponse(true, "Token refreshed successfully");
            response.setAccessToken(newAccessToken);
            response.setRefreshToken(newRefreshToken);
            response.setUserName(loginUsername);
            response.setUser(userPayload);

            auditSuccess("TOKEN_REFRESH", String.valueOf(user.getId()), "Refresh token rotated successfully");

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                    .body(response);
        } catch (Exception e) {
            auditFailure("TOKEN_REFRESH", "N/A", "Refresh token validation failed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid refresh token or user login");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody(required = false) RefreshTokenRequest request,
                                   @CookieValue(name = "refreshToken", required = false) String cookieRefreshToken) {
        String tokenToRevoke = null;

        if (request != null && request.getRefreshToken() != null && !request.getRefreshToken().isBlank()) {
            tokenToRevoke = request.getRefreshToken();
        } else if (cookieRefreshToken != null && !cookieRefreshToken.isBlank()) {
            tokenToRevoke = cookieRefreshToken;
        }

        if (tokenToRevoke != null && !tokenToRevoke.isBlank()) {
            try {
                String username = jwtService.extractRefreshClaims(tokenToRevoke).getSubject();
                User user = findUserByLoginIdentifier(username).orElse(null);
                if (user != null) {
                    refreshTokenService.revokeAllActiveTokensForUser(user);
                    auditSuccess("LOGOUT", String.valueOf(user.getId()), "Revoked all active tokens for user");
                } else {
                    refreshTokenService.revokeToken(tokenToRevoke);
                    auditSuccess("LOGOUT", "N/A", "Revoked token without resolved user");
                }
            } catch (Exception e) {
                refreshTokenService.revokeToken(tokenToRevoke);
                auditFailure("LOGOUT", "N/A", "Token revoke fallback after parsing failure");
            }
        }

        ResponseCookie clearedCookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearedCookie.toString())
                .body(new ApiResponse(true, "Logged out successfully"));
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse> signup(@RequestBody SignupRequest request) {
        try {
            String normalizedEmail = userProvisioningService.requireNormalizedEmail(request);
            String normalizedUsername = userProvisioningService.requireNormalizedUsername(request);

            if (userRepo.existsByUsername(normalizedUsername)) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Username already exists"));
            }

            if (userRepo.existsByEmail(normalizedEmail)) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Email already exists"));
            }

            Optional<User> creatorOpt = resolveAuthenticatedCreator();
            User user = userProvisioningService.provisionUser(request, normalizedEmail, normalizedUsername, creatorOpt);

            userRepo.save(user);

            if (creatorOpt.isPresent()) {
                auditSuccess("ROLE_PERMISSION_CHANGE", String.valueOf(user.getId()), "Child user created under creator tenant context");
                return ResponseEntity.ok(new ApiResponse(true, "User created"));
            }

            auditSuccess("ROLE_PERMISSION_CHANGE", String.valueOf(user.getId()), "User role assigned during signup");

            return ResponseEntity.ok(new ApiResponse(true, "Account created successfully"));

        } catch (SecurityException e) {
            auditFailure("SIGNUP", "N/A", "Forbidden signup flow: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (IllegalArgumentException e) {
            auditFailure("SIGNUP", "N/A", "Validation failed: " + e.getMessage());
            return ResponseEntity.badRequest()
                .body(new ApiResponse(false, e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            auditFailure("SIGNUP", "N/A", "Duplicate username or email");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiResponse(false, "Username or email already exists"));
        } catch (Exception e) {
            auditFailure("SIGNUP", "N/A", "Server error during signup");
            return ResponseEntity.internalServerError()
                    .body(new ApiResponse(false, "Server error: " + e.getMessage()));
        }
    }

    private Optional<User> resolveAuthenticatedCreator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return Optional.empty();
        }

        String principal = authentication.getName().trim();
        Optional<User> byEmail = userRepo.findByEmail(principal);
        if (byEmail.isPresent()) {
            return byEmail;
        }

        Optional<User> byUsername = userRepo.findByUsername(principal);
        return byUsername == null ? Optional.empty() : byUsername;
    }

    private Optional<User> findUserByLoginIdentifier(String loginIdentifier) {
        if (loginIdentifier == null || loginIdentifier.isBlank()) {
            return Optional.empty();
        }

        String trimmedIdentifier = loginIdentifier.trim();
        String normalizedUsername = trimmedIdentifier.toLowerCase(Locale.ROOT);

        Optional<User> userByUsername = userRepo.findByUsername(normalizedUsername);
        if (userByUsername != null && userByUsername.isPresent()) {
            return ensureTenant(userByUsername.get());
        }

        Optional<User> userByEmail = userRepo.findByEmail(trimmedIdentifier);
        if (!userByEmail.isPresent()) {
            userByEmail = userRepo.findByEmail(trimmedIdentifier.toLowerCase(Locale.ROOT));
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
        if (user.getTenant() == null) {
            user.setTenant(tenantRepo.save(buildTenantReferenceForExistingUser(user)));
            userRepo.save(user);
        }
        return Optional.of(user);
    }

    private Tenant buildTenantReferenceForExistingUser(User user) {
        String businessName = user.getBusinessName() == null || user.getBusinessName().isBlank()
                ? "Default Business"
                : user.getBusinessName().trim();
        String base = businessName.replaceAll("[^A-Za-z0-9]", "");
        base = base.isBlank() ? "TEN" : base.toUpperCase();
        String identity = user.getEmail() == null ? String.valueOf(System.currentTimeMillis()) : user.getEmail().trim();
        int hash = Math.abs((identity + "-" + businessName).hashCode()) % 9000;
        int suffix = 1000 + hash;

        String prefix = base.length() > 4 ? base.substring(0, 4) : base;
        if (prefix.length() < 4) {
            prefix = (prefix + "TEN").substring(0, 4);
        }

        Tenant tenant = new Tenant();
        tenant.setName(businessName);
        tenant.setCode(prefix + String.valueOf(suffix).substring(Math.max(0, String.valueOf(suffix).length() - 4)));
        return tenant;
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
