package org.ib.ibbackend.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.ib.ibbackend.dto.*;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.PasswordSecurityService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.ib.ibbackend.service.UserProvisioningService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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
        Optional<User> userOpt = findUserByLoginIdentifier(req.getUsername());
        if (userOpt.isEmpty()) {
            auditFailure("LOGIN", "N/A", "Unknown username");
            return ResponseEntity.status(401).build();
        }

        User user = userOpt.get();

        if (!passwordSecurityService.matches(req.getPassword(), user.getPasswordHash())) {
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
        userPayload.put("id", user.getId());
        userPayload.put("username", user.getOwnerName());
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

        try {
            User user = findUserByLoginIdentifier(request != null ? request.getUsername() : null)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            if (!refreshTokenService.isValidForUser(user, incomingRefreshToken)) {
                auditFailure("TOKEN_REFRESH", String.valueOf(user.getId()), "Invalid refresh token for user");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or expired refresh token");
            }

            String newAccessToken = jwtService.generateAccessToken(user);
            String newRefreshToken = jwtService.generateRefreshToken(user);
            refreshTokenService.rotateToken(user, incomingRefreshToken, newRefreshToken);

            ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", newRefreshToken)
                    .httpOnly(true)
                    .secure(true)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(Duration.ofDays(7))
                    .build();

            Map<String, Object> userPayload = new HashMap<>();
            userPayload.put("id", user.getId());
            userPayload.put("username", user.getOwnerName());
            userPayload.put("name", user.getOwnerName());
            userPayload.put("email", user.getEmail());
            userPayload.put("role", user.getRole());
            userPayload.put("businessName", user.getBusinessName());
            userPayload.put("tenantId", user.getTenant() != null ? user.getTenant().getId() : null);

            RefreshTokenResponse response = new RefreshTokenResponse(true, "Token refreshed successfully");
            response.setAccessToken(newAccessToken);
            response.setRefreshToken(newRefreshToken);
            response.setUserName(user.getOwnerName());
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
            if (userRepo.existsByEmail(normalizedEmail)) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Email already exists"));
            }

            Optional<User> creatorOpt = resolveAuthenticatedCreator();
            User user = userProvisioningService.provisionUser(request, normalizedEmail, creatorOpt);

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
        return userRepo.findByOwnerName(principal);
    }

    private Optional<User> findUserByLoginIdentifier(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }

        Optional<User> userByEmail = userRepo.findByEmail(username);
        if (userByEmail.isPresent()) {
            return ensureTenant(userByEmail.get());
        }

        return userRepo.findByOwnerName(username)
                .flatMap(this::ensureTenant);
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
}