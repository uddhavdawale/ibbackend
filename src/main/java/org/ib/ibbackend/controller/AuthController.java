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
import org.ib.ibbackend.service.RefreshTokenService;
import org.ib.ibbackend.service.AuthCookieService;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Arrays;
import io.jsonwebtoken.JwtException;
import org.springframework.http.CacheControl;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private TenantRepository tenantRepo;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private AuthCookieService authCookieService = AuthCookieService.defaultFactory();

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:3000,http://localhost:8080,https://ibfrontend.vercel.app}")
    private String allowedOrigins;

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

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(
            @CookieValue(name = "refreshToken", required = false) String cookieRefreshToken,
            HttpServletRequest request) {
        if (hasUntrustedOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("message", "Request origin is not allowed"));
        }
        if (cookieRefreshToken == null || cookieRefreshToken.isBlank()) {
            auditFailure("TOKEN_REFRESH", "N/A", "Refresh token missing");
            return refreshUnauthorized("Refresh token missing");
        }

        final String username;
        try {
            username = jwtService.extractRefreshClaims(cookieRefreshToken).getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            auditFailure("TOKEN_REFRESH", "N/A", "Invalid or expired refresh token");
            return refreshUnauthorized("Invalid or expired refresh token");
        }

        User user = findUserByLoginIdentifier(username).orElse(null);
        if (user == null) {
            auditFailure("TOKEN_REFRESH", "N/A", "Refresh token user not found");
            return refreshUnauthorized("Invalid or expired refresh token");
        }
        if (user.isDisabled() || UserRolePolicy.isDisabledRole(user.getRole())) {
            refreshTokenService.revokeAllActiveTokensForUser(user);
            auditFailure("TOKEN_REFRESH", String.valueOf(user.getId()), "Disabled account refresh attempt");
            return refreshUnauthorized("Invalid or expired refresh token");
        }

        String newAccessToken = jwtService.generateAccessToken(user);
        String candidateRefreshToken = jwtService.generateRefreshToken(user);
        Optional<String> rotatedRefreshToken =
                refreshTokenService.rotateToken(user, cookieRefreshToken, candidateRefreshToken);
        if (rotatedRefreshToken.isEmpty()) {
            auditFailure("TOKEN_REFRESH", String.valueOf(user.getId()), "Invalid or reused refresh token");
            return refreshUnauthorized("Invalid or expired refresh token");
        }

        String loginUsername = resolveLoginUsername(user);
        Map<String, Object> userPayload = new HashMap<>();
        userPayload.put("id", user.getId());
        userPayload.put("username", loginUsername);
        userPayload.put("name", user.getOwnerName());
        userPayload.put("email", user.getEmail());
        userPayload.put("role", user.getRole());
        userPayload.put("businessName", user.getBusinessName());
        userPayload.put("tenantId", user.getTenant() != null ? user.getTenant().getId() : null);

        RefreshTokenResponse response = new RefreshTokenResponse(true, "Token refreshed successfully");
        response.setAccessToken(newAccessToken);
        response.setUserName(loginUsername);
        response.setUser(userPayload);
        auditSuccess("TOKEN_REFRESH", String.valueOf(user.getId()), "Refresh token rotated successfully");

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, authCookieService.refreshCookie(
                        rotatedRefreshToken.get(), jwtService.getRefreshTokenExpiryMs()).toString())
                .header(HttpHeaders.SET_COOKIE, authCookieService.clearLegacyRefreshCookie().toString())
                .body(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @CookieValue(name = "refreshToken", required = false) String cookieRefreshToken,
            HttpServletRequest request) {
        if (hasUntrustedOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("message", "Request origin is not allowed"));
        }
        if (cookieRefreshToken != null && !cookieRefreshToken.isBlank()) {
            refreshTokenService.revokeFamilyForToken(cookieRefreshToken);
            auditSuccess("LOGOUT", "N/A", "Revoked refresh-token session");
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, authCookieService.clearRefreshCookie().toString())
                .header(HttpHeaders.SET_COOKIE, authCookieService.clearLegacyRefreshCookie().toString())
                .body(new ApiResponse(true, "Logged out successfully"));
    }

    private ResponseEntity<?> refreshUnauthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, authCookieService.clearRefreshCookie().toString())
                .header(HttpHeaders.SET_COOKIE, authCookieService.clearLegacyRefreshCookie().toString())
                .body(Map.of("message", message));
    }

    private boolean hasUntrustedOrigin(HttpServletRequest request) {
        String origin = request == null ? null : request.getHeader(HttpHeaders.ORIGIN);
        return origin != null && !origin.isBlank()
                && Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .noneMatch(origin::equals);
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
        tenant.setAddress(user.getAddress());
        tenant.setCity(user.getCity());
        tenant.setPincode(user.getPincode());
        tenant.setGstin(validTenantGstin(user.getGstin()));
        tenant.setPhone(safeTenantPhone(user.getPhone()));
        tenant.setEmail(user.getEmail());
        return tenant;
    }

    private String validTenantGstin(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]")
                ? normalized : null;
    }

    private String safeTenantPhone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= 32 ? normalized : null;
    }

}
