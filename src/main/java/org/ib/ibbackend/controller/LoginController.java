package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.LoginModel;
import org.ib.ibbackend.dto.LoginRequest;
import org.ib.ibbackend.dto.LoginResponse;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.FirebaseTokenVerifierService;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.MailMfaService;
import org.ib.ibbackend.service.PasswordSecurityService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController()
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
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
    private MailMfaService mailMfaService;

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

    @PostMapping(path = {"/api/login", "/login"})
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        Optional<User> userOpt = findUserByLoginIdentifier(request.getUsername());

        if (userOpt.isEmpty()) {
            auditFailure("LOGIN", "N/A", "Unknown username");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginResponse(false, "Invalid username or password"));
        }

        User user = userOpt.get();

        if (!passwordSecurityService.matches(request.getPassword(), user.getPasswordHash())) {
            auditFailure("LOGIN", String.valueOf(user.getId()), "Invalid password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginResponse(false, "Invalid username or password"));
        }

        boolean firebaseMfaEnabled = isFirebaseMfaEnabled();
        boolean mailMfaEnabled = mailMfaService != null && mailMfaService.isEnabled();

        if (firebaseMfaEnabled || mailMfaEnabled) {
            if (firebaseMfaEnabled) {
                auditSuccess("MFA_CHALLENGE", String.valueOf(user.getId()), "Firebase MFA challenge required");
                LoginResponse response = new LoginResponse(false, "MFA verification required");
                response.setRequiresMfa(true);
                response.setUsername(user.getOwnerName());
                response.setRole(user.getRole());
                response.setEmail(user.getEmail());
                response.setUser(Map.of(
                        "id", user.getId(),
                        "username", user.getOwnerName(),
                        "name", user.getOwnerName(),
                        "email", user.getEmail(),
                        "role", user.getRole(),
                        "mfaProvider", "FIREBASE"
                ));
                return ResponseEntity.ok(response);
            }

            try {
                mailMfaService.issueOtp(user.getEmail());
                auditSuccess("MFA_CHALLENGE", String.valueOf(user.getId()), "Mail OTP challenge issued");
            } catch (RuntimeException ex) {
                ex.printStackTrace();
                auditFailure("MFA_CHALLENGE", String.valueOf(user.getId()), "MFA email could not be sent");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new LoginResponse(false, "MFA email could not be sent. Check SMTP configuration."));
            }

            LoginResponse response = new LoginResponse(false, "MFA verification required");
            response.setRequiresMfa(true);
            response.setUsername(user.getOwnerName());
            response.setRole(user.getRole());
            response.setEmail(user.getEmail());
            response.setUser(Map.of(
                    "id", user.getId(),
                    "username", user.getOwnerName(),
                    "name", user.getOwnerName(),
                    "email", user.getEmail(),
                    "role", user.getRole(),
                    "mfaProvider", "MAIL"
            ));
            return ResponseEntity.ok(response);
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

        LoginResponse response = new LoginResponse(true, "Login successful");
        response.setToken(accessToken);
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        response.setTokenType("Bearer");
        response.setUsername(user.getOwnerName());
        response.setRole(user.getRole());
        response.setEmail(user.getEmail());
        response.setUser(userPayload);

        auditSuccess("LOGIN", String.valueOf(user.getId()), "Login successful");

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(response);
    }

    private Optional<User> findUserByLoginIdentifier(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }

        Optional<User> userByEmail = userRepository.findByEmail(username);
        if (userByEmail.isPresent()) {
            return ensureTenant(userByEmail.get());
        }

        return userRepository.findByOwnerName(username)
                .flatMap(this::ensureTenant);
    }

    private Optional<User> ensureTenant(User user) {
        if (user == null) {
            return Optional.empty();
        }
        if (user.getTenant() == null) {
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
}
