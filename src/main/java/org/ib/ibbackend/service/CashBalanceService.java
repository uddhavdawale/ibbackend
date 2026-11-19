package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CashBalanceInitializationRequest;
import org.ib.ibbackend.dto.CashOpeningBalanceRequest;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.CashBalanceLedgerRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CashBalanceService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final CashBalanceLedgerRepository ledgerRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public CashBalanceService(CashBalanceLedgerRepository ledgerRepository,
                              UserRepository userRepository,
                              AuditLogService auditLogService) {
        this.ledgerRepository = ledgerRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public Map<String, Object> initialize(CashBalanceInitializationRequest request) {
        int tenantId = requireTenantId();
        User actor = requireActor(tenantId);
        requireAdmin(actor);
        validateInitialization(request);

        List<User> collectors = activeCollectors(tenantId);
        Map<Long, CashBalanceInitializationRequest.OpeningBalance> requested =
                new HashMap<>();
        Set<String> openingKeys = new HashSet<>();
        for (CashBalanceInitializationRequest.OpeningBalance entry : request.getOpeningBalances()) {
            if (entry == null || entry.getUserId() == null
                    || requested.put(entry.getUserId(), entry) != null
                    || !openingKeys.add(entry.getIdempotencyKey().trim())) {
                throw badRequest("Opening balances must contain unique cashier IDs");
            }
        }
        Set<Long> eligibleIds = new HashSet<>();
        collectors.forEach(collector -> eligibleIds.add(collector.getId()));
        if (!requested.keySet().equals(eligibleIds)) {
            throw badRequest("Provide an audited opening balance for every active cashier in this tenant");
        }

        Instant existingStart = ledgerRepository.findTrackingStartedAt(tenantId);
        if (existingStart != null) {
            Instant existingForKey = ledgerRepository.findTrackingStartedAtForKey(
                    tenantId, request.getIdempotencyKey().trim());
            if (existingForKey != null && existingForKey.equals(
                    request.getTrackingStartedAt().truncatedTo(ChronoUnit.MICROS))) {
                for (CashBalanceInitializationRequest.OpeningBalance entry : requested.values()) {
                    if (!matchesExistingOpening(tenantId, entry)) {
                        throw conflict("Cash balance initialization key was reused with different values");
                    }
                }
                return Map.of("trackingStartedAt", existingStart, "initialized", true);
            }
            throw conflict("Cash balance tracking has already been initialized");
        }

        int inserted = ledgerRepository.initialize(tenantId, actor.getId(),
                request.getTrackingStartedAt().truncatedTo(ChronoUnit.MICROS),
                request.getIdempotencyKey().trim());
        if (inserted != 1) {
            throw conflict("Cash balance tracking was initialized concurrently; refresh and retry");
        }
        for (CashBalanceInitializationRequest.OpeningBalance entry : requested.values()) {
            if (ledgerRepository.insertOpeningBalance(tenantId, entry.getUserId(), actor.getId(),
                    money(entry.getAmount()), entry.getNote().trim(), entry.getIdempotencyKey().trim()) != 1) {
                throw conflict("An opening balance already exists; refresh and retry");
            }
        }
        auditLogService.success("CASH_BALANCE_TRACKING_INITIALIZED", Integer.toString(tenantId),
                "Cash tracking initialized with audited opening balances");
        return Map.of("trackingStartedAt",
                request.getTrackingStartedAt().truncatedTo(ChronoUnit.MICROS), "initialized", true);
    }

    @Transactional
    public Map<String, Object> recordOpeningBalance(CashOpeningBalanceRequest request) {
        int tenantId = requireTenantId();
        User actor = requireActor(tenantId);
        requireAdmin(actor);
        validateOpeningBalance(request);
        if (ledgerRepository.findTrackingStartedAt(tenantId) == null) {
            throw conflict("Cash tracking must be initialized before adding a cashier opening balance");
        }
        User cashier = userRepository.findByIdAndTenant_Id(request.getUserId(), tenantId)
                .filter(this::isActive)
                .filter(this::isCashCollector)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Active cashier not found in this tenant"));

        CashBalanceLedgerRepository.OpeningBalance existing =
                ledgerRepository.findOpeningBalance(tenantId, cashier.getId());
        String key = request.getIdempotencyKey().trim();
        String note = request.getNote().trim();
        BigDecimal amount = money(request.getAmount());
        if (existing != null) {
            if (existing.amount().compareTo(amount) == 0
                    && existing.note().equals(note)
                    && existing.idempotencyKey().equals(key)) {
                return openingBalanceView(cashier.getId(), amount);
            }
            throw conflict("An opening balance already exists for this cashier");
        }
        if (ledgerRepository.insertOpeningBalance(
                tenantId, cashier.getId(), actor.getId(), amount, note, key) != 1) {
            existing = ledgerRepository.findOpeningBalance(tenantId, cashier.getId());
            if (existing != null && existing.amount().compareTo(amount) == 0
                    && existing.note().equals(note) && existing.idempotencyKey().equals(key)) {
                return openingBalanceView(cashier.getId(), amount);
            }
            throw conflict("An opening balance already exists for this cashier");
        }
        auditLogService.success("CASH_OPENING_BALANCE_RECORDED", cashier.getId().toString(),
                "An audited cash opening balance was recorded");
        return openingBalanceView(cashier.getId(), amount);
    }

    @Transactional
    public Map<String, Object> listRecipients() {
        int tenantId = requireTenantId();
        User actor = requireActor(tenantId);
        if (!isCashCollector(actor)) {
            throw new AccessDeniedException("Cash collection tickets are not permitted for this user");
        }
        List<Map<String, Object>> recipients = userRepository.findTenantStoreAdmins(tenantId).stream()
                .filter(this::isActive)
                .map(user -> {
                    Map<String, Object> recipient = new LinkedHashMap<>();
                    recipient.put("id", user.getId());
                    recipient.put("name", displayName(user));
                    return recipient;
                })
                .toList();
        return Map.of("content", recipients);
    }

    @Transactional
    public Map<String, Object> getBalances(int page, int size) {
        int tenantId = requireTenantId();
        User actor = requireActor(tenantId);
        boolean cashierRequest = isCashCollector(actor);
        if (!cashierRequest
                && !UserRolePolicy.isStoreAdmin(actor.getRole())
                && !UserRolePolicy.isSystemAdmin(actor.getRole())) {
            throw new AccessDeniedException("Cash balance access is not permitted for this user");
        }
        Instant trackingStartedAt = ledgerRepository.findTrackingStartedAt(tenantId);
        if (trackingStartedAt == null) {
            ledgerRepository.ensureAutomaticTracking(tenantId);
            trackingStartedAt = ledgerRepository.findTrackingStartedAt(tenantId);
        }
        if (trackingStartedAt == null) {
            throw conflict("Cash balance tracking could not be initialized. Please retry the request.");
        }
        if (page < 0 || size < 1) {
            throw badRequest("Page must be zero or greater and size must be positive");
        }
        int pageSize = Math.min(size, 100);

        Instant asOf = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<Map<String, Object>> balances =
                new ArrayList<>(ledgerRepository.findBalances(tenantId, trackingStartedAt, asOf));
        if (cashierRequest) {
            Map<String, Object> ownBalance = balances.stream()
                    .filter(balance -> actor.getId().equals(balance.get("userId")))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Cashier balance is unavailable"));
            return Map.of("balance", ownBalance);
        }
        requireAdmin(actor);

        balances.sort(Comparator
                .<Map<String, Object>, BigDecimal>comparing(balance -> (BigDecimal) balance.get("dueToSubmit"))
                .reversed()
                .thenComparing(balance -> (String) balance.get("userName"),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(balance -> (Long) balance.get("userId")));
        Map<String, Object> summary = summarize(balances, asOf);
        int fromIndex = (int) Math.min((long) page * pageSize, balances.size());
        int toIndex = Math.min(fromIndex + pageSize, balances.size());
        int totalPages = balances.isEmpty() ? 0 : (balances.size() + pageSize - 1) / pageSize;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", new ArrayList<>(balances.subList(fromIndex, toIndex)));
        response.put("summary", summary);
        response.put("page", page);
        response.put("size", pageSize);
        response.put("totalPages", totalPages);
        response.put("totalElements", balances.size());
        return response;
    }

    private Map<String, Object> summarize(List<Map<String, Object>> balances, Instant asOf) {
        BigDecimal totalDue = ZERO;
        BigDecimal totalPending = ZERO;
        BigDecimal totalDisputed = ZERO;
        BigDecimal totalAvailable = ZERO;
        for (Map<String, Object> balance : balances) {
            totalDue = totalDue.add((BigDecimal) balance.get("dueToSubmit"));
            totalPending = totalPending.add((BigDecimal) balance.get("pendingHandoverAmount"));
            totalDisputed = totalDisputed.add((BigDecimal) balance.get("disputedHandoverAmount"));
            totalAvailable = totalAvailable.add((BigDecimal) balance.get("availableToSubmit"));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("asOf", asOf);
        summary.put("totalDueToSubmit", totalDue);
        summary.put("totalPendingHandoverAmount", totalPending);
        summary.put("totalDisputedHandoverAmount", totalDisputed);
        summary.put("totalAvailableToSubmit", totalAvailable);
        return summary;
    }

    private Map<String, Object> openingBalanceView(Long userId, BigDecimal amount) {
        return Map.of("userId", userId, "openingAmount", amount, "recorded", true);
    }

    private boolean matchesExistingOpening(
            int tenantId, CashBalanceInitializationRequest.OpeningBalance entry) {
        CashBalanceLedgerRepository.OpeningBalance existing =
                ledgerRepository.findOpeningBalance(tenantId, entry.getUserId());
        return existing != null
                && existing.amount().compareTo(money(entry.getAmount())) == 0
                && existing.note().equals(entry.getNote().trim())
                && existing.idempotencyKey().equals(entry.getIdempotencyKey().trim());
    }

    private List<User> activeCollectors(int tenantId) {
        return userRepository.findTenantCashCollectors(tenantId).stream()
                .filter(this::isActive)
                .filter(this::isCashCollector)
                .toList();
    }

    private User requireActor(int tenantId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AccessDeniedException("Authentication required");
        }
        User user = userRepository.findByEmail(authentication.getName())
                .or(() -> userRepository.findByUsername(authentication.getName()))
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
        if (user.getId() == null || user.getTenant() == null || user.getTenant().getId() != tenantId
                || !isActive(user)) {
            throw new AccessDeniedException("Access denied");
        }
        return user;
    }

    private int requireTenantId() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) throw new AccessDeniedException("Access denied");
        return Math.toIntExact(tenantId);
    }

    private void requireAdmin(User actor) {
        if (!UserRolePolicy.isStoreAdmin(actor.getRole())
                && !UserRolePolicy.isSystemAdmin(actor.getRole())) {
            throw new AccessDeniedException("Cash balance administration is restricted to tenant admins");
        }
    }

    private boolean isCashCollector(User user) {
        String role = UserRolePolicy.normalize(user.getRole());
        return "billing_manager".equals(role) || "store_manager".equals(role);
    }

    private boolean isActive(User user) {
        return user != null && user.getDeletedAt() == null && !user.isDisabled()
                && !UserRolePolicy.isDisabledRole(user.getRole());
    }

    private String displayName(User user) {
        if (user.getOwnerName() != null && !user.getOwnerName().isBlank()) {
            return user.getOwnerName().trim();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername().trim();
        }
        return user.getEmail();
    }

    private void validateInitialization(CashBalanceInitializationRequest request) {
        if (request == null || request.getTrackingStartedAt() == null
                || request.getTrackingStartedAt().isAfter(Instant.now())
                || !validKey(request.getIdempotencyKey())
                || request.getOpeningBalances() == null
                || request.getOpeningBalances().size() > 1000) {
            throw badRequest("Invalid cash balance initialization request");
        }
        for (CashBalanceInitializationRequest.OpeningBalance entry : request.getOpeningBalances()) {
            validateOpeningFields(entry == null ? null : entry.getUserId(),
                    entry == null ? null : entry.getAmount(),
                    entry == null ? null : entry.getNote(),
                    entry == null ? null : entry.getIdempotencyKey());
        }
    }

    private void validateOpeningBalance(CashOpeningBalanceRequest request) {
        if (request == null) {
            throw badRequest("Invalid cash opening balance request");
        }
        validateOpeningFields(request.getUserId(), request.getAmount(),
                request.getNote(), request.getIdempotencyKey());
    }

    private void validateOpeningFields(Long userId, BigDecimal amount, String note, String key) {
        if (userId == null || userId <= 0 || amount == null || amount.scale() > 2
                || amount.precision() > 12 || note == null || note.isBlank()
                || note.length() > 1000 || !validKey(key)) {
            throw badRequest("Each opening balance requires a cashier, signed amount, audit note, and idempotency key");
        }
    }

    private boolean validKey(String key) {
        return key != null && !key.isBlank() && key.length() <= 100
                && !key.trim().toUpperCase(java.util.Locale.ROOT).startsWith("AUTO-ONBOARDING-");
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
