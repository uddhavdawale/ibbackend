package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CashHandoverConfirmationRequest;
import org.ib.ibbackend.dto.CashHandoverRequest;
import org.ib.ibbackend.dto.CashHandoverResolutionRequest;
import org.ib.ibbackend.dto.CashCollectionTicketRequest;
import org.ib.ibbackend.entity.CashCollectionPaymentProof;
import org.ib.ibbackend.entity.CashHandover;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.CashHandoverRepository;
import org.ib.ibbackend.repository.CashCollectionPaymentProofRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class CashHandoverService {
    private static final long MAX_PAYMENT_PROOF_SIZE_BYTES = 5 * 1024 * 1024;
    private static final Set<String> PAYMENT_PROOF_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp");

    private final CashHandoverRepository handoverRepository;
    private final CashCollectionPaymentProofRepository paymentProofRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final TenantDocumentEncryptionService documentEncryptionService;

    public CashHandoverService(CashHandoverRepository handoverRepository,
                               CashCollectionPaymentProofRepository paymentProofRepository,
                               UserRepository userRepository,
                               AuditLogService auditLogService,
                               TenantDocumentEncryptionService documentEncryptionService) {
        this.handoverRepository = handoverRepository;
        this.paymentProofRepository = paymentProofRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.documentEncryptionService = documentEncryptionService;
    }

    @Transactional
    public Map<String, Object> create(CashHandoverRequest request) {
        return create(request, false, "CASH", null, null, null);
    }

    @Transactional
    public Map<String, Object> createCollectionTicket(CashHandoverRequest request) {
        return create(request, true, "CASH", null, null, null);
    }

    @Transactional
    public Map<String, Object> createCollectionTicket(CashCollectionTicketRequest request) {
        return createCollectionTicket(request, null);
    }

    @Transactional
    public Map<String, Object> createCollectionTicket(
            CashCollectionTicketRequest request, MultipartFile paymentProof) {
        if (request == null || request.getRecipientUserId() == null
                || request.getRecipientUserId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Store admin recipient is required");
        }
        int tenantId = requireTenantId();
        User actor = requireActor(tenantId);
        requireCashier(actor);
        String paymentMethod = normalizePaymentMethod(request.getPaymentMethod());
        byte[] proofContent = readPaymentProof(paymentMethod, paymentProof);
        String proofContentType = proofContent == null ? null : detectImageContentType(proofContent);
        String proofSha256 = proofContent == null ? null : sha256(proofContent);

        String identifier = request.getRecipientUserId().trim();
        Long recipientId;
        if (identifier.matches("\\d+")) {
            try {
                recipientId = Long.valueOf(identifier);
            } catch (NumberFormatException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid store admin recipient");
            }
        } else {
            List<Long> matchingAdmins =
                    userRepository.findActiveStoreAdminIdsByTenantAndIdentifier(tenantId, identifier);
            if (matchingAdmins.size() != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Recipient must uniquely identify an active store admin in this tenant");
            }
            recipientId = matchingAdmins.get(0);
        }

        CashHandoverRequest handoverRequest = new CashHandoverRequest();
        handoverRequest.setRecipientUserId(recipientId);
        handoverRequest.setDeclaredAmount(request.getDeclaredAmount());
        handoverRequest.setIdempotencyKey(request.getIdempotencyKey());
        handoverRequest.setNotes(request.getNotes());
        return create(handoverRequest, true, paymentMethod, proofContentType, proofContent, proofSha256);
    }

    private Map<String, Object> create(
            CashHandoverRequest request,
            boolean collectionTicket,
            String paymentMethod,
            String proofContentType,
            byte[] proofContent,
            String proofSha256) {
        validateCreateRequest(request);
        int tenantId = requireTenantId();
        User actor = requireActor(tenantId);
        requireCashier(actor);

        Map<Long, User> lockedUsers = lockUsers(tenantId, actor.getId(), request.getRecipientUserId());
        User sender = lockedUsers.get(actor.getId());
        if (!isActive(sender)) {
            throw new AccessDeniedException("Cash sender is not active");
        }
        requireCashier(sender);
        if (collectionTicket && isStoreAdminOrSystemAdmin(sender)) {
            throw new AccessDeniedException("Admins cannot submit their own cash collection tickets");
        }
        String key = request.getIdempotencyKey().trim();
        BigDecimal amount = money(request.getDeclaredAmount());

        CashHandover prior = handoverRepository
                .findByTenant_IdAndSender_IdAndIdempotencyKey(tenantId, sender.getId(), key)
                .orElse(null);
        if (prior != null) {
            if (collectionTicket && !UserRolePolicy.isStoreAdmin(prior.getRecipient().getRole())) {
                throw conflict("Idempotency key was already used for a different cash handover");
            }
            if (prior.getRecipient().getId().equals(request.getRecipientUserId())
                    && prior.getDeclaredAmount().compareTo(amount) == 0
                    && Objects.equals(prior.getNotes(), sanitize(request.getNotes()))
                    && Objects.equals(effectivePaymentMethod(prior), paymentMethod)
                    && Objects.equals(prior.getPaymentProofSha256(), proofSha256)) {
                return toView(prior, null);
            }
            throw conflict("Idempotency key was already used for a different cash handover");
        }

        User recipient = lockedUsers.get(request.getRecipientUserId());
        if (!isActive(recipient)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Active recipient not found in this tenant");
        }
        if (collectionTicket && !UserRolePolicy.isStoreAdmin(recipient.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cash collection tickets must be submitted to a store admin");
        }
        if (sender.getId().equals(recipient.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sender and recipient must be different users");
        }
        requireCashier(recipient);

        BigDecimal available = null;
        if ("CASH".equals(paymentMethod)) {
            available = availableCash(tenantId, sender.getId());
            if (amount.compareTo(available) > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Handover amount exceeds the sender's available cash");
            }
        }

        byte[] encryptedProof = proofContent == null
                ? null : documentEncryptionService.encrypt(proofContent);
        CashHandover handover = new CashHandover();
        handover.setTenant(tenantReference(tenantId));
        handover.setSender(sender);
        handover.setRecipient(recipient);
        handover.setDeclaredAmount(amount);
        handover.setPaymentMethod(paymentMethod);
        handover.setPaymentProofSha256(proofSha256);
        handover.setIdempotencyKey(key);
        handover.setNotes(sanitize(request.getNotes()));
        handover.setStatus("PENDING");
        handover.setSenderConfirmedAt(Instant.now());
        CashHandover saved = handoverRepository.saveAndFlush(handover);
        if (encryptedProof != null) {
            CashCollectionPaymentProof proof = new CashCollectionPaymentProof();
            proof.setTicketId(saved.getId());
            proof.setContentType(proofContentType);
            proof.setEncryptedContent(encryptedProof);
            proof.setSha256(proofSha256);
            paymentProofRepository.save(proof);
        }
        auditLogService.success("CASH_HANDOVER_SUBMITTED", saved.getId().toString(),
                "Cash collection ticket submitted via " + paymentMethod + " to user " + recipient.getId());
        return toView(saved, available == null ? null : available.subtract(amount));
    }

    @Transactional
    public Map<String, Object> confirm(Long handoverId, CashHandoverConfirmationRequest request) {
        return confirm(handoverId, request, false);
    }

    @Transactional
    public Map<String, Object> countCollectionTicket(
            Long handoverId, CashHandoverConfirmationRequest request) {
        return confirm(handoverId, request, true);
    }

    private Map<String, Object> confirm(
            Long handoverId, CashHandoverConfirmationRequest request, boolean collectionTicket) {
        validateConfirmationRequest(request);
        int tenantId = requireTenantId();
        User actor = requireActor(tenantId);
        requireCashier(actor);
        CashHandover handover = handoverRepository.findByIdAndTenantIdForUpdate(handoverId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cash handover not found"));
        if (collectionTicket && !UserRolePolicy.isStoreAdmin(handover.getRecipient().getRole())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cash collection ticket not found");
        }
        if (!handover.getRecipient().getId().equals(actor.getId())) {
            throw new AccessDeniedException("Only the designated recipient can confirm this handover");
        }
        Map<Long, User> participants = lockUsers(
                tenantId, handover.getSender().getId(), handover.getRecipient().getId());
        User lockedRecipient = participants.get(actor.getId());
        if (!isActive(lockedRecipient)) {
            throw new AccessDeniedException("Cash recipient is not active");
        }
        requireCashier(lockedRecipient);
        String key = request.getIdempotencyKey().trim();
        BigDecimal countedAmount = money(request.getCountedAmount());

        if (!"PENDING".equals(handover.getStatus())) {
            if (key.equals(handover.getReceiverConfirmationKey())
                    && handover.getReceiverCountedAmount().compareTo(countedAmount) == 0) {
                return toConfirmationView(handover, tenantId, actor, collectionTicket);
            }
            throw conflict("Cash handover has already been confirmed or is no longer pending");
        }
        if (handover.getRecipient().getTenant() == null
                || handover.getRecipient().getTenant().getId() != tenantId) {
            throw new AccessDeniedException("Cash handover recipient does not belong to this tenant");
        }
        if (handoverRepository.findByTenant_IdAndReceiverConfirmationKey(tenantId, key).isPresent()) {
            throw conflict("Confirmation idempotency key was already used");
        }

        handover.setReceiverCountedAmount(countedAmount);
        handover.setReceiverConfirmationKey(key);
        handover.setRecipientConfirmedAt(Instant.now());
        handover.setRecipientNotes(sanitize(request.getNotes()));
        handover.setStatus(countedAmount.compareTo(handover.getDeclaredAmount()) == 0
                ? "ACCEPTED" : "DISPUTED");
        CashHandover saved = collectionTicket
                ? handoverRepository.saveAndFlush(handover)
                : handoverRepository.save(handover);
        auditLogService.success("ACCEPTED".equals(saved.getStatus())
                        ? "CASH_HANDOVER_ACCEPTED" : "CASH_HANDOVER_DISPUTED",
                saved.getId().toString(), "Recipient counted " + countedAmount.toPlainString());
        return toConfirmationView(saved, tenantId, actor, collectionTicket);
    }

    @Transactional
    public Map<String, Object> resolve(Long handoverId, CashHandoverResolutionRequest request) {
        return resolve(handoverId, request, false);
    }

    @Transactional
    public Map<String, Object> resolveCollectionTicket(Long handoverId, CashHandoverResolutionRequest request) {
        Map<String, Object> result = resolve(handoverId, request, true);
        if ("RESOLVED".equals(result.get("status"))) {
            int tenantId = requireTenantId();
            Long recipientId = ((Number) result.get("recipientUserId")).longValue();
            result.put("storeCashAvailable", availableCash(tenantId, recipientId));
        }
        return result;
    }

    private Map<String, Object> resolve(
            Long handoverId, CashHandoverResolutionRequest request, boolean collectionTicket) {
        validateResolutionRequest(request);
        int tenantId = requireTenantId();
        User actor = requireActor(tenantId);
        if (!canResolve(actor)) {
            throw new AccessDeniedException("Cash handover resolution is restricted to tenant admins");
        }
        CashHandover handover = handoverRepository.findByIdAndTenantIdForUpdate(handoverId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cash handover not found"));
        if (collectionTicket && !UserRolePolicy.isStoreAdmin(handover.getRecipient().getRole())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cash collection ticket not found");
        }
        lockUsers(tenantId, handover.getSender().getId(), handover.getRecipient().getId());
        String key = request.getIdempotencyKey().trim();
        BigDecimal amount = money(request.getResolvedAmount());

        if ("RESOLVED".equals(handover.getStatus())) {
            if (key.equals(handover.getResolutionIdempotencyKey())
                    && handover.getResolvedAmount().compareTo(amount) == 0
                    && Objects.equals(handover.getResolutionNote(), sanitize(request.getResolutionNote()))) {
                return toView(handover, null);
            }
            throw conflict("Cash handover has already been resolved");
        }
        if (!"DISPUTED".equals(handover.getStatus())) {
            throw conflict("Only disputed cash handovers can be resolved");
        }
        if (handoverRepository.findByTenant_IdAndResolutionIdempotencyKey(tenantId, key).isPresent()) {
            throw conflict("Resolution idempotency key was already used");
        }
        BigDecimal maximumResolvable = handover.getDeclaredAmount()
                .min(handover.getReceiverCountedAmount());
        if (amount.compareTo(maximumResolvable) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Resolved amount cannot exceed the declared or receiver-counted amount");
        }

        handover.setResolvedAmount(amount);
        handover.setResolutionIdempotencyKey(key);
        handover.setResolutionNote(sanitize(request.getResolutionNote()));
        handover.setResolvedByUserId(actor.getId());
        handover.setResolvedAt(Instant.now());
        handover.setStatus("RESOLVED");
        CashHandover saved = collectionTicket
                ? handoverRepository.saveAndFlush(handover)
                : handoverRepository.save(handover);
        auditLogService.success("CASH_HANDOVER_RESOLVED", saved.getId().toString(),
                "Cash handover resolved at " + amount.toPlainString());
        return toView(saved, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long handoverId) {
        int tenantId = requireTenantId();
        User actor = requireActor(tenantId);
        boolean canViewAll = canViewAll(actor);
        CashHandover handover = handoverRepository
                .findVisibleById(handoverId, tenantId, actor.getId(), canViewAll)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cash handover not found"));
        return toView(handover, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(String status, int page, int size) {
        int tenantId = requireTenantId();
        User actor = requireActor(tenantId);
        String normalizedStatus = normalizeStatus(status);
        Page<CashHandover> records = handoverRepository.findVisibleHistory(
                tenantId, actor.getId(), canViewAll(actor), normalizedStatus,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.unsorted()));
        Map<String, Object> result = new HashMap<>();
        result.put("content", records.map(record -> toView(record, null)).getContent());
        result.put("page", records.getNumber());
        result.put("size", records.getSize());
        result.put("totalPages", records.getTotalPages());
        result.put("totalElements", records.getTotalElements());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listCollectionTickets(String status, int page, int size) {
        int tenantId = requireTenantId();
        User actor = requireActor(tenantId);
        String normalizedStatus = normalizeStatus(status);
        Page<CashHandover> records = handoverRepository.findVisibleCollectionTickets(
                tenantId, actor.getId(), isStoreAdminOrSystemAdmin(actor), normalizedStatus,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.unsorted()));
        Map<String, Object> result = new HashMap<>();
        result.put("content", records.map(record -> toView(record, null)).getContent());
        result.put("page", records.getNumber());
        result.put("size", records.getSize());
        result.put("totalPages", records.getTotalPages());
        result.put("totalElements", records.getTotalElements());
        return result;
    }

    @Transactional(readOnly = true)
    public PaymentProof getCollectionTicketPaymentProof(Long ticketId) {
        int tenantId = requireTenantId();
        User actor = requireActor(tenantId);
        CashHandover ticket = handoverRepository
                .findVisibleCollectionTicketById(ticketId, tenantId, actor.getId(),
                        isStoreAdminOrSystemAdmin(actor))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Cash collection ticket not found"));
        CashCollectionPaymentProof proof = paymentProofRepository.findById(ticket.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Payment proof not found"));
        if (!Objects.equals(ticket.getPaymentProofSha256(), proof.getSha256())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment proof not found");
        }
        return new PaymentProof(
                proof.getContentType(),
                documentEncryptionService.decrypt(proof.getEncryptedContent()));
    }

    private Map<String, Object> toConfirmationView(
            CashHandover handover, int tenantId, User recipient, boolean collectionTicket) {
        Map<String, Object> view = toView(handover, null);
        if (collectionTicket && "ACCEPTED".equals(handover.getStatus())) {
            view.put("storeCashAvailable", availableCash(tenantId, recipient.getId()));
        }
        return view;
    }

    private BigDecimal availableCash(int tenantId, Long userId) {
        BigDecimal available = handoverRepository.getAvailableCash(tenantId, userId);
        return available == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : money(available).max(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    private boolean isStoreAdminOrSystemAdmin(User user) {
        return UserRolePolicy.isStoreAdmin(user.getRole()) || UserRolePolicy.isSystemAdmin(user.getRole());
    }

    private Map<Long, User> lockUsers(int tenantId, Long firstUserId, Long secondUserId) {
        Long lowerId = Math.min(firstUserId, secondUserId);
        Long higherId = Math.max(firstUserId, secondUserId);
        User lower = userRepository.findTenantUserRecordByIdForUpdate(lowerId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Active tenant member not found"));
        if (lowerId.equals(higherId)) {
            return Map.of(lowerId, lower);
        }
        User higher = userRepository.findTenantUserRecordByIdForUpdate(higherId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Active tenant member not found"));
        Map<Long, User> users = new HashMap<>();
        users.put(lowerId, lower);
        users.put(higherId, higher);
        return users;
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

    private void requireCashier(User user) {
        String role = UserRolePolicy.normalize(user.getRole());
        if (!"store_admin".equals(role) && !"billing_manager".equals(role)
                && !"store_manager".equals(role) && !UserRolePolicy.isSystemAdmin(role)) {
            throw new AccessDeniedException("Cash handover is not permitted for this user");
        }
    }

    private boolean canViewAll(User user) {
        String role = UserRolePolicy.normalize(user.getRole());
        return "store_admin".equals(role) || "billing_manager".equals(role)
                || UserRolePolicy.isSystemAdmin(role);
    }

    private boolean canResolve(User user) {
        String role = UserRolePolicy.normalize(user.getRole());
        return "store_admin".equals(role) || UserRolePolicy.isSystemAdmin(role);
    }

    private boolean isActive(User user) {
        return user != null && user.getDeletedAt() == null && !user.isDisabled()
                && !UserRolePolicy.isDisabledRole(user.getRole());
    }

    private int requireTenantId() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) throw new AccessDeniedException("Access denied");
        return Math.toIntExact(tenantId);
    }

    private void validateCreateRequest(CashHandoverRequest request) {
        if (request == null || request.getRecipientUserId() == null
                || request.getDeclaredAmount() == null
                || request.getDeclaredAmount().compareTo(BigDecimal.ZERO) <= 0
                || request.getDeclaredAmount().scale() > 2
                || request.getDeclaredAmount().precision() > 12
                || !validKey(request.getIdempotencyKey())
                || !validNote(request.getNotes())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cash handover request");
        }
    }

    private void validateConfirmationRequest(CashHandoverConfirmationRequest request) {
        if (request == null || request.getCountedAmount() == null
                || request.getCountedAmount().compareTo(BigDecimal.ZERO) < 0
                || request.getCountedAmount().scale() > 2
                || request.getCountedAmount().precision() > 12
                || !validKey(request.getIdempotencyKey())
                || !validNote(request.getNotes())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cash handover confirmation");
        }
    }

    private void validateResolutionRequest(CashHandoverResolutionRequest request) {
        if (request == null || request.getResolvedAmount() == null
                || request.getResolvedAmount().compareTo(BigDecimal.ZERO) < 0
                || request.getResolvedAmount().scale() > 2
                || request.getResolvedAmount().precision() > 12
                || !validKey(request.getIdempotencyKey())
                || request.getResolutionNote() == null || request.getResolutionNote().isBlank()
                || request.getResolutionNote().length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cash handover resolution");
        }
    }

    private boolean validKey(String key) {
        return key != null && !key.isBlank() && key.length() <= 100;
    }

    private boolean validNote(String note) {
        return note == null || note.length() <= 1000;
    }

    private String normalizePaymentMethod(String method) {
        if (method == null || method.isBlank()) return "CASH";
        String normalized = method.trim().toUpperCase(Locale.ROOT);
        if ("UPI/CARD".equals(normalized) || "UPICARD".equals(normalized)) {
            return "UPI_CARD";
        }
        if (!"CASH".equals(normalized) && !"UPI".equals(normalized)
                && !"CARD".equals(normalized) && !"UPI_CARD".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payment method");
        }
        return normalized;
    }

    private String effectivePaymentMethod(CashHandover handover) {
        return handover.getPaymentMethod() == null ? "CASH" : handover.getPaymentMethod();
    }

    private byte[] readPaymentProof(String paymentMethod, MultipartFile paymentProof) {
        boolean hasProof = paymentProof != null && !paymentProof.isEmpty();
        if ("CASH".equals(paymentMethod)) {
            if (hasProof) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Payment screenshots are only accepted for UPI or card submissions");
            }
            return null;
        }
        if (!hasProof) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A payment screenshot is required for UPI or card submissions");
        }
        if (paymentProof.getSize() > MAX_PAYMENT_PROOF_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Payment screenshots must be 5 MB or smaller");
        }
        String declaredContentType = paymentProof.getContentType();
        String normalizedDeclaredContentType = declaredContentType == null
                ? "" : declaredContentType.trim().toLowerCase(Locale.ROOT);
        boolean genericContentType = normalizedDeclaredContentType.isEmpty()
                || "application/octet-stream".equals(normalizedDeclaredContentType);
        if (!genericContentType && !PAYMENT_PROOF_CONTENT_TYPES.contains(normalizedDeclaredContentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Payment screenshot must be a PNG, JPEG, or WebP image");
        }
        try {
            byte[] content = paymentProof.getBytes();
            if (content.length > MAX_PAYMENT_PROOF_SIZE_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Payment screenshots must be 5 MB or smaller");
            }
            String detectedContentType = detectImageContentType(content);
            if (!genericContentType && !detectedContentType.equals(normalizedDeclaredContentType)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Payment screenshot content does not match its content type");
            }
            return content;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read payment screenshot", exception);
        }
    }

    private String detectImageContentType(byte[] content) {
        if (content.length >= 8
                && content[0] == (byte) 0x89 && content[1] == 0x50 && content[2] == 0x4E
                && content[3] == 0x47 && content[4] == 0x0D && content[5] == 0x0A
                && content[6] == 0x1A && content[7] == 0x0A) {
            return "image/png";
        }
        if (content.length >= 3 && (content[0] & 0xFF) == 0xFF
                && (content[1] & 0xFF) == 0xD8 && (content[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (content.length >= 12 && content[0] == 'R' && content[1] == 'I'
                && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E'
                && content[10] == 'B' && content[11] == 'P') {
            return "image/webp";
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Payment screenshot must be a PNG, JPEG, or WebP image");
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!"PENDING".equals(normalized) && !"ACCEPTED".equals(normalized)
                && !"DISPUTED".equals(normalized) && !"RESOLVED".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported cash handover status");
        }
        return normalized;
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private Tenant tenantReference(int tenantId) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        return tenant;
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private Map<String, Object> toView(CashHandover handover, BigDecimal senderAvailableCash) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", handover.getId());
        view.put("senderUserId", handover.getSender().getId());
        view.put("senderName", handover.getSender().getOwnerName());
        view.put("recipientUserId", handover.getRecipient().getId());
        view.put("recipientName", handover.getRecipient().getOwnerName());
        view.put("declaredAmount", handover.getDeclaredAmount());
        view.put("paymentMethod", effectivePaymentMethod(handover));
        boolean hasPaymentProof = handover.getPaymentProofSha256() != null;
        view.put("paymentProofAvailable", hasPaymentProof);
        view.put("paymentProofUrl", hasPaymentProof
                ? "/api/cash-collection-tickets/" + handover.getId() + "/payment-proof" : null);
        view.put("receiverCountedAmount", handover.getReceiverCountedAmount());
        view.put("resolvedAmount", handover.getResolvedAmount());
        view.put("status", handover.getStatus());
        view.put("notes", handover.getNotes());
        view.put("recipientNotes", handover.getRecipientNotes());
        view.put("resolutionNote", handover.getResolutionNote());
        view.put("resolvedByUserId", handover.getResolvedByUserId());
        view.put("senderConfirmedAt", handover.getSenderConfirmedAt());
        view.put("recipientConfirmedAt", handover.getRecipientConfirmedAt());
        view.put("resolvedAt", handover.getResolvedAt());
        view.put("createdAt", handover.getCreatedAt());
        view.put("updatedAt", handover.getUpdatedAt());
        if (senderAvailableCash != null) view.put("senderAvailableCash", senderAvailableCash);
        return view;
    }

    public record PaymentProof(String contentType, byte[] content) {
    }
}
