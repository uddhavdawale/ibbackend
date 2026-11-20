package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.UserRenameRequest;
import org.ib.ibbackend.entity.RetailerStatus;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.MailMfaService;
import org.ib.ibbackend.service.PasswordResetService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@CrossOrigin(originPatterns  = "*")
@RequestMapping("/api/users")
public class UserController {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9._-]{3,50}$");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final long RESEND_WINDOW_MS = 5 * 60 * 1000L;
    private static final int RESEND_MAX_PER_ACTOR = 5;
    private static final int RESEND_MAX_PER_TARGET = 3;

    private static final ConcurrentHashMap<String, WindowCounter> RESEND_BY_ACTOR = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, WindowCounter> RESEND_BY_TARGET = new ConcurrentHashMap<>();

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final MailMfaService mailMfaService;
    private final AuditLogService auditLogService;
    private final PasswordResetService passwordResetService;

    public UserController(UserRepository userRepository) {
        this(userRepository, null, null, null, null);
    }

    @Autowired
    public UserController(UserRepository userRepository,
                          RefreshTokenService refreshTokenService,
                          MailMfaService mailMfaService,
                          AuditLogService auditLogService,
                          PasswordResetService passwordResetService) {
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.mailMfaService = mailMfaService;
        this.auditLogService = auditLogService;
        this.passwordResetService = passwordResetService;
    }

    public UserController(UserRepository userRepository,
                          RefreshTokenService refreshTokenService,
                          MailMfaService mailMfaService,
                          AuditLogService auditLogService) {
        this(userRepository, refreshTokenService, mailMfaService, auditLogService, null);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getCurrentUserCompatibility() {
        // Compatibility route for clients still calling /api/users.
        return getCurrentUser();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        User user = resolveCurrentUser();

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", user.getId());
        payload.put("email", user.getEmail());
        payload.put("businessName", user.getBusinessName());
        payload.put("ownerName", user.getOwnerName());
        payload.put("phone", user.getPhone());
        payload.put("role", user.getRole());
        payload.put("gstin", user.getGstin());
        payload.put("pan", user.getPan());
        payload.put("address", user.getAddress());
        payload.put("city", user.getCity());
        payload.put("pincode", user.getPincode());
        payload.put("tenantId", user.getTenant() != null ? user.getTenant().getId() : null);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("user", payload);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/existing")
    public ResponseEntity<Map<String, Object>> getExistingUsers(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        User currentUser = requireAdminUser();

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size <= 0 ? DEFAULT_PAGE_SIZE : size));
        PageRequest pageRequest = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id"));

        String normalizedSearch = search == null ? null : search.trim();
        Page<User> userPage;
        Integer tenantId = currentUser.getTenant() != null ? currentUser.getTenant().getId() : null;

        if (UserRolePolicy.isSystemAdmin(currentUser.getRole())) {
            userPage = userRepository.findAllUsersForManagement(normalizedSearch, includeDisabled, pageRequest);
        } else {
            if (tenantId == null) {
                throw new AccessDeniedException("Current user has no tenant");
            }
            userPage = userRepository.findTenantUsersForManagement(

                    normalizedSearch,
                    includeDisabled,
                    pageRequest
            );
        }

        List<Map<String, Object>> items = userPage.getContent()
                .stream()
                .map(user -> toManagedUserPayload(user, currentUser))
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("items", items);
        response.put("page", userPage.getNumber());
        response.put("size", userPage.getSize());
        response.put("totalItems", userPage.getTotalElements());
        response.put("totalPages", userPage.getTotalPages());
        response.put("hasMore", userPage.hasNext());
        response.put("tenantId", tenantId);
        response.put("platformWide", UserRolePolicy.isSystemAdmin(currentUser.getRole()));
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{userId}/rename")
    public ResponseEntity<Map<String, Object>> renameUser(@PathVariable("userId") Long userId,
                                                          @RequestBody UserRenameRequest request) {
        User currentUser = requireAdminUser();
        User targetUser = resolveTenantScopedUser(userId, currentUser);

        String normalizedUsername = null;
        if (request != null && request.getUsername() != null && !request.getUsername().isBlank()) {
            normalizedUsername = request.getUsername().trim().toLowerCase(Locale.ROOT);
            if (!USERNAME_PATTERN.matcher(normalizedUsername).matches()) {
                return ResponseEntity.badRequest().body(error("Username must be 3-50 characters and contain only letters, numbers, dot, underscore, or hyphen"));
            }
            if (!normalizedUsername.equals(targetUser.getUsername())
                    && userRepository.existsByUsernameAndIdNot(normalizedUsername, targetUser.getId())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(error("Username already exists"));
            }
            targetUser.setUsername(normalizedUsername);
        }

        if (request != null && request.getOwnerName() != null && !request.getOwnerName().isBlank()) {
            targetUser.setOwnerName(request.getOwnerName().trim());
        }

        if ((request == null || request.getUsername() == null || request.getUsername().isBlank())
                && (request == null || request.getOwnerName() == null || request.getOwnerName().isBlank())) {
            return ResponseEntity.badRequest().body(error("At least one field (username or ownerName) is required"));
        }

        User saved = userRepository.save(targetUser);
        auditSuccess("USER_RENAMED", String.valueOf(saved.getId()), "Store admin updated user display identity");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "User updated successfully");
        response.put("user", toManagedUserPayload(saved, currentUser));
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable("userId") Long userId,
                                                           @RequestBody UserRenameRequest request) {
        // Backward-compatible edit endpoint currently supports rename fields.
        return renameUser(userId, request);
    }

    @PostMapping("/{userId}/disable")
    public ResponseEntity<Map<String, Object>> disableUser(@PathVariable("userId") Long userId) {
        // Compatibility endpoint retained for clients already using /disable.
        return setUserDisabled(userId, true);
    }

    @PatchMapping("/{userId}/status")
    public ResponseEntity<Map<String, Object>> updateUserStatus(@PathVariable("userId") Long userId,
                                                                 @RequestBody Map<String, Object> body) {
        if (body == null || !body.containsKey("disabled")) {
            return ResponseEntity.badRequest().body(errorWithCode("disabled flag is required", "VALIDATION_ERROR"));
        }
        Object disabledValue = body.get("disabled");
        if (!(disabledValue instanceof Boolean)) {
            return ResponseEntity.badRequest().body(errorWithCode("disabled must be a boolean", "VALIDATION_ERROR"));
        }
        boolean disabled = (Boolean) disabledValue;
        return setUserDisabled(userId, disabled);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable("userId") Long userId) {
        User currentUser = requireAdminUser();
        if (currentUser.getId().equals(userId)) {
            return ResponseEntity.badRequest().body(error("You cannot delete your own account"));
        }

        User targetUser = resolveTenantScopedUser(userId, currentUser);
        if (refreshTokenService != null) {
            refreshTokenService.revokeAllActiveTokensForUser(targetUser);
        }
        targetUser.setDeletedAt(LocalDateTime.now());
        targetUser.setDeletedBy(currentUser.getId());
        targetUser.setDisabled(true);
        userRepository.save(targetUser);

        auditSuccess("USER_DELETED", String.valueOf(userId), "Store admin soft-deleted user");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "User deleted successfully");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{userId}/resend-password")
    public ResponseEntity<Map<String, Object>> resendPassword(@PathVariable("userId") Long userId) {
        User currentUser = requireAdminUser();
        User targetUser = resolveTenantScopedUser(userId, currentUser);

        String tenantKey = String.valueOf(currentUser.getTenant().getId());
        String actorKey = tenantKey + ":" + currentUser.getId();
        String targetKey = tenantKey + ":" + targetUser.getId();
        if (!allowWithinRateLimit(RESEND_BY_ACTOR, actorKey, RESEND_MAX_PER_ACTOR)
                || !allowWithinRateLimit(RESEND_BY_TARGET, targetKey, RESEND_MAX_PER_TARGET)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(errorWithCode("Too many resend requests. Please try again later.", "RESEND_RATE_LIMITED"));
        }

        if (targetUser.getEmail() == null || targetUser.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body(error("User email is missing"));
        }
        if (mailMfaService == null || passwordResetService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(error("Password recovery is temporarily unavailable"));
        }

        Optional<PasswordResetService.ResetEmail> resetEmail =
                passwordResetService.requestReset(targetUser.getEmail());
        if (resetEmail.isPresent()) {
            PasswordResetService.ResetEmail email = resetEmail.get();
            try {
                mailMfaService.sendPasswordReset(email.email(), email.token());
            } catch (MailMfaService.MailQueueFullException | IllegalStateException ex) {
                passwordResetService.invalidateResetToken(email.token());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(error("Password recovery is temporarily unavailable"));
            }
        }
        auditSuccess("PASSWORD_RESEND", String.valueOf(targetUser.getId()), "Store admin requested password recovery");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "If the account is eligible, password recovery instructions will be sent.");
        response.put("user", toManagedUserPayload(targetUser, currentUser));
        return ResponseEntity.ok(response);
    }

    private User resolveCurrentUser() {
        String principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getName();

        if (principal == null || principal.isBlank()) {
            throw new AccessDeniedException("Access denied");
        }

        Optional<User> byEmail = userRepository.findByEmail(principal.trim());
        if (byEmail.isPresent()) {
            return byEmail.get();
        }

        return userRepository.findByUsername(principal.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
    }

    private User requireAdminUser() {
        User currentUser = resolveCurrentUser();
        if (!UserRolePolicy.canManageUsers(currentUser.getRole())) {
            throw new AccessDeniedException("Only system admins can manage users");
        }
        if (currentUser.getTenant() == null && !UserRolePolicy.isSystemAdmin(currentUser.getRole())) {
            throw new AccessDeniedException("Current user has no tenant");
        }
        return currentUser;
    }

    private User resolveTenantScopedUser(Long userId, User currentUser) {
        if (UserRolePolicy.isSystemAdmin(currentUser.getRole())) {
            return userRepository.findById(userId)
                    .orElseThrow(() -> new AccessDeniedException("User not found"));
        }
        return userRepository.findByIdAndTenant_Id(userId, currentUser.getTenant().getId())
                .orElseThrow(() -> new AccessDeniedException("User not found in your tenant"));
    }

    private Map<String, Object> toManagedUserPayload(User user, User currentUser) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", user.getId());
        payload.put("username", user.getUsername());
        payload.put("email", user.getEmail());
        payload.put("ownerName", user.getOwnerName());
        payload.put("businessName", user.getBusinessName());
        payload.put("phone", user.getPhone());
        payload.put("role", user.getRole());
        payload.put("tenantId", user.getTenant() != null ? user.getTenant().getId() : null);
        payload.put("createdAt", user.getCreatedAt());
        boolean effectiveDisabled = user.isDisabled() || UserRolePolicy.isDisabledRole(user.getRole());
        payload.put("disabled", effectiveDisabled);
        payload.put("status", effectiveDisabled ? "DISABLED" : "ACTIVE");
        payload.put("currentUser", currentUser != null && currentUser.getId() != null && currentUser.getId().equals(user.getId()));
        return payload;
    }

    private Map<String, Object> error(String message) {
        return errorWithCode(message, "VALIDATION_ERROR");
    }

    private Map<String, Object> errorWithCode(String message, String code) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        response.put("code", code);
        return response;
    }

    private ResponseEntity<Map<String, Object>> setUserDisabled(Long userId, boolean disabled) {
        User currentUser = requireAdminUser();
        if (currentUser.getId().equals(userId) && disabled) {
            return ResponseEntity.badRequest().body(error("You cannot disable your own account"));
        }

        User targetUser = resolveTenantScopedUser(userId, currentUser);
        RetailerStatus approvalStatus = targetUser.getApprovalStatus();
        if (!disabled && approvalStatus != RetailerStatus.APPROVED
                && approvalStatus != RetailerStatus.DISABLED) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(errorWithCode("User cannot be enabled while approval status is " + approvalStatus,
                            "INVALID_STATUS_TRANSITION"));
        }

        targetUser.setDisabled(disabled);
        if (disabled && approvalStatus == RetailerStatus.APPROVED) {
            targetUser.setApprovalStatus(RetailerStatus.DISABLED);
        } else if (!disabled) {
            targetUser.setApprovalStatus(RetailerStatus.APPROVED);
        }
        userRepository.save(targetUser);

        if (refreshTokenService != null) {
            refreshTokenService.revokeAllActiveTokensForUser(targetUser);
        }

        auditSuccess(disabled ? "USER_DISABLED" : "USER_ENABLED",
                String.valueOf(targetUser.getId()),
                disabled ? "System admin disabled user access" : "System admin enabled user access");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", disabled ? "User disabled successfully" : "User enabled successfully");
        response.put("user", toManagedUserPayload(targetUser, currentUser));
        return ResponseEntity.ok(response);
    }

    private boolean allowWithinRateLimit(ConcurrentHashMap<String, WindowCounter> bucket, String key, int maxAllowed) {
        long now = System.currentTimeMillis();
        WindowCounter counter = bucket.computeIfAbsent(key, ignored -> new WindowCounter(now));
        synchronized (counter) {
            if (now - counter.windowStartMs >= RESEND_WINDOW_MS) {
                counter.windowStartMs = now;
                counter.count.set(0);
            }
            if (counter.count.get() >= maxAllowed) {
                return false;
            }
            counter.count.incrementAndGet();
            return true;
        }
    }

    private static final class WindowCounter {
        private long windowStartMs;
        private final AtomicInteger count = new AtomicInteger(0);

        private WindowCounter(long windowStartMs) {
            this.windowStartMs = windowStartMs;
        }
    }

    private void auditSuccess(String action, String resourceId, String detail) {
        if (auditLogService != null) {
            auditLogService.success(action, resourceId, detail);
        }
    }
}
