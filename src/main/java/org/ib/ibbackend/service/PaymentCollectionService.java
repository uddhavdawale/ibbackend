package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CashReconciliationRequest;
import org.ib.ibbackend.dto.CollectPaymentRequest;
import org.ib.ibbackend.dto.RefundPaymentRequest;
import org.ib.ibbackend.entity.CashReconciliation;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.NonGstInvoice;
import org.ib.ibbackend.entity.PaymentRecord;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.CashReconciliationRepository;
import org.ib.ibbackend.repository.CashHandoverRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.NonGstInvoiceRepository;
import org.ib.ibbackend.repository.PaymentRecordRepository;
import org.ib.ibbackend.repository.PaymentReportRow;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentCollectionService {
    private static final ZoneId RETAILER_ZONE = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final PaymentRecordRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final NonGstInvoiceRepository nonGstInvoiceRepository;
    private final UserRepository userRepository;
    private final CashReconciliationRepository reconciliationRepository;
    private final CashHandoverRepository handoverRepository;
    private final AuditLogService auditLogService;

    public PaymentCollectionService(
            PaymentRecordRepository paymentRepository,
            InvoiceRepository invoiceRepository,
            NonGstInvoiceRepository nonGstInvoiceRepository,
            UserRepository userRepository,
            CashReconciliationRepository reconciliationRepository,
            CashHandoverRepository handoverRepository,
            AuditLogService auditLogService) {
        this.paymentRepository = paymentRepository;
        this.invoiceRepository = invoiceRepository;
        this.nonGstInvoiceRepository = nonGstInvoiceRepository;
        this.userRepository = userRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.handoverRepository = handoverRepository;
        this.auditLogService = auditLogService;
    }

    private boolean canRefund(User user) {
        String role = UserRolePolicy.normalize(user.getRole());
        return "store_admin".equals(role) || UserRolePolicy.isSystemAdmin(role);
    }

    @Transactional
    public Map<String, Object> collectGstPayment(Long invoiceId, CollectPaymentRequest request) {
        Integer tenantId = requireTenantId();
        User collector = lockCollector(tenantId, requireCollector(tenantId, true), true);
        Invoice invoice = invoiceRepository.findByIdAndTenantIdForUpdate(invoiceId, tenantId.longValue())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        ensureInvoiceCollectible(invoice.getStatus() == Invoice.InvoiceStatus.CANCELLED);
        return collect(invoice, null, tenantId, collector, request);
    }

    @Transactional
    public Map<String, Object> collectNonGstPayment(Long invoiceId, CollectPaymentRequest request) {
        Integer tenantId = requireTenantId();
        User collector = lockCollector(tenantId, requireCollector(tenantId, true), true);
        NonGstInvoice invoice = nonGstInvoiceRepository.findByIdAndTenantIdForUpdate(invoiceId, tenantId.longValue())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        ensureInvoiceCollectible("CANCELLED".equalsIgnoreCase(invoice.getStatus()));
        return collect(null, invoice, tenantId, collector, request);
    }

    @Transactional
    public Map<String, Object> refundPayment(Long paymentId, RefundPaymentRequest request) {
        Integer tenantId = requireTenantId();
        User actor = lockCollector(tenantId, requireCollector(tenantId, false), false);
        if (!canRefund(actor)) {
            throw new AccessDeniedException("Payment refunds are not permitted");
        }
        validateRefundRequest(request);
        String key = request.getIdempotencyKey().trim();

        PaymentRecord existing = paymentRepository.findByTenant_IdAndIdempotencyKey(tenantId, key).orElse(null);
        PaymentRecord original = paymentRepository.findByIdAndTenantId(paymentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        if (existing != null) {
            if (sameRefund(existing, original, request)) return toPaymentView(existing);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was already used");
        }
        if ("REFUND".equals(original.getSource()) || !"RECEIVED".equals(original.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only received payments can be refunded");
        }

        Invoice invoice = null;
        NonGstInvoice nonGstInvoice = null;
        if (original.getInvoice() != null) {
            invoice = invoiceRepository.findByIdAndTenantIdForUpdate(
                            original.getInvoice().getId(), tenantId.longValue())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        } else if (original.getNonGstInvoice() != null) {
            nonGstInvoice = nonGstInvoiceRepository.findByIdAndTenantIdForUpdate(
                            original.getNonGstInvoice().getId(), tenantId.longValue())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment is not attached to an invoice");
        }

        original = paymentRepository.findByIdAndTenantIdForUpdate(paymentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        BigDecimal amount = money(request.getAmount());
        BigDecimal alreadyRefunded = paymentRepository.sumRefundsForPayment(paymentId, tenantId);
        if (amount.compareTo(original.getAmount().subtract(alreadyRefunded).max(BigDecimal.ZERO)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund exceeds the unrefunded payment amount");
        }
        BigDecimal invoicePaidAmount = invoice != null ? invoice.getPaidAmount() : nonGstInvoice.getPaidAmount();
        if (amount.compareTo(invoicePaidAmount) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund exceeds the invoice's recorded paid balance");
        }

        PaymentRecord refund = new PaymentRecord();
        refund.setTenant(tenantReference(tenantId));
        refund.setInvoice(invoice);
        refund.setNonGstInvoice(nonGstInvoice);
        refund.setReversalOf(original);
        refund.setCollector(actor);
        refund.setAmount(amount);
        refund.setMethod(original.getMethod());
        refund.setStatus("REFUNDED");
        refund.setReferenceNo(sanitize(request.getReason(), 255));
        refund.setReceiptNo("RFND-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT));
        refund.setIdempotencyKey(key);
        refund.setSource("REFUND");
        refund.setCollectedAt(Instant.now());
        refund = paymentRepository.save(refund);

        if (invoice != null) {
            BigDecimal newPaid = invoice.getPaidAmount().subtract(amount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            invoice.setPaidAmount(newPaid);
            if (invoice.getStatus() != Invoice.InvoiceStatus.CANCELLED) {
                invoice.setStatus(newPaid.compareTo(invoice.getGrandTotal()) >= 0
                        ? Invoice.InvoiceStatus.PAID : Invoice.InvoiceStatus.PENDING);
                invoice.setPaymentStatus(newPaid.compareTo(invoice.getGrandTotal()) >= 0
                        ? Invoice.PaymentStatus.PAID
                        : newPaid.signum() > 0 ? Invoice.PaymentStatus.PARTIAL : Invoice.PaymentStatus.PENDING);
            }
            invoiceRepository.save(invoice);
        } else {
            BigDecimal newPaid = nonGstInvoice.getPaidAmount().subtract(amount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            nonGstInvoice.setPaidAmount(newPaid);
            if (!"CANCELLED".equalsIgnoreCase(nonGstInvoice.getStatus())) {
                String newStatus = newPaid.compareTo(nonGstInvoice.getGrandTotal()) >= 0
                        ? "PAID" : newPaid.signum() > 0 ? "PARTIAL" : "PENDING";
                nonGstInvoice.setPaymentStatus(newStatus);
                nonGstInvoice.setStatus(newStatus);
            }
            nonGstInvoiceRepository.save(nonGstInvoice);
        }

        auditLogService.success("PAYMENT_REFUND", refund.getReceiptNo(),
                "Payment refund recorded against receipt " + original.getReceiptNo());
        return toPaymentView(refund);
    }

    @Transactional
    public void recordInitialPayment(Invoice invoice, User collector) {
        if (invoice.getPaidAmount().compareTo(BigDecimal.ZERO) <= 0) return;
        CollectPaymentRequest request = new CollectPaymentRequest();
        request.setAmount(money(invoice.getPaidAmount()));
        request.setMethod(invoice.getPaymentMethod() == null || invoice.getPaymentMethod().isBlank()
                ? "OTHER" : invoice.getPaymentMethod());
        request.setIdempotencyKey("initial-gst-" + invoice.getId());
        record(invoice, null, invoice.getTenant().getId(), collector, request, "INITIAL");
    }

    @Transactional
    public void recordInitialPayment(NonGstInvoice invoice, User collector) {
        if (invoice.getPaidAmount().compareTo(BigDecimal.ZERO) <= 0) return;
        CollectPaymentRequest request = new CollectPaymentRequest();
        request.setAmount(money(invoice.getPaidAmount()));
        request.setMethod(invoice.getPaymentMethod() == null || invoice.getPaymentMethod().isBlank()
                ? "OTHER" : invoice.getPaymentMethod());
        request.setIdempotencyKey("initial-non-gst-" + invoice.getId());
        record(null, invoice, invoice.getTenant().getId(), collector, request, "INITIAL");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getReport(LocalDate from, LocalDate to, Long requestedCollectorId,
                                         String method, Long invoiceId, int page, int size) {
        Integer tenantId = requireTenantId();
        User user = requireCollector(tenantId, false);
        Long collectorId = canViewAll(user) ? requestedCollectorId : user.getId();
        String normalizedMethod = normalizeOptionalMethod(method);
        Instant fromTime = from.atStartOfDay(RETAILER_ZONE).toInstant();
        Instant toTime = to.plusDays(1).atStartOfDay(RETAILER_ZONE).toInstant();

        Page<PaymentReportRow> rows = paymentRepository.findReportRows(
                tenantId, fromTime, toTime, collectorId, normalizedMethod, invoiceId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.unsorted()));
        Object[] totals = paymentRepository.summarize(
                tenantId, fromTime, toTime, collectorId, normalizedMethod, invoiceId);

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalCollected", decimalAt(totals, 0));
        summary.put("totalTransactions", longAt(totals, 1));
        summary.put("cashCollected", decimalAt(totals, 2));
        summary.put("upiCollected", decimalAt(totals, 3));
        summary.put("cardCollected", decimalAt(totals, 4));
        summary.put("totalRefunds", decimalAt(totals, 5));
        summary.put("outstandingAmount",
                invoiceRepository.getPendingAmountByTenantId(tenantId.longValue())
                        .add(nonGstInvoiceRepository.getPendingAmountByTenantId(tenantId.longValue())));

        Map<String, Object> result = new HashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("summary", summary);
        result.put("payments", rows.map(this::toPaymentView).getContent());
        result.put("page", rows.getNumber());
        result.put("size", rows.getSize());
        result.put("totalPages", rows.getTotalPages());
        result.put("totalElements", rows.getTotalElements());
        return result;
    }

    @Transactional
    public Map<String, Object> reconcileCash(CashReconciliationRequest request) {
        if (request == null || request.getReconciliationDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reconciliation date is required");
        }
        if (request.getOpeningFloat() == null || request.getPhysicalCashCounted() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Opening float and physical count are required");
        }
        if (request.getOpeningFloat().compareTo(BigDecimal.ZERO) < 0
                || request.getPhysicalCashCounted().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cash values cannot be negative");
        }

        Integer tenantId = requireTenantId();
        User collector = lockCollector(tenantId, requireCollector(tenantId, true), true);
        if (request.getReconciliationDate().isAfter(LocalDate.now(RETAILER_ZONE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reconciliation date cannot be in the future");
        }

        Instant from = request.getReconciliationDate().atStartOfDay(RETAILER_ZONE).toInstant();
        Instant to = request.getReconciliationDate().plusDays(1).atStartOfDay(RETAILER_ZONE).toInstant();
        BigDecimal cashCollected = paymentRepository.sumCashByCollectorAndPeriod(
                tenantId, collector.getId(), from, to);
        BigDecimal cashRefunds = paymentRepository.sumCashRefundsByCollectorAndPeriod(
                tenantId, collector.getId(), from, to);
        BigDecimal incomingHandovers = handoverRepository.sumIncomingCompletedForPeriod(
                tenantId, collector.getId(), from, to);
        BigDecimal outgoingHandovers = handoverRepository.sumOutgoingCompletedForPeriod(
                tenantId, collector.getId(), from, to);
        BigDecimal openingFloat = money(request.getOpeningFloat());
        BigDecimal expected = openingFloat.add(cashCollected).subtract(cashRefunds)
                .add(incomingHandovers).subtract(outgoingHandovers)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal physical = money(request.getPhysicalCashCounted());
        BigDecimal difference = physical.subtract(expected).setScale(2, RoundingMode.HALF_UP);

        CashReconciliation reconciliation = new CashReconciliation();
        reconciliation.setTenant(tenantReference(tenantId));
        reconciliation.setStaffUser(collector);
        reconciliation.setReconciliationDate(request.getReconciliationDate());
        reconciliation.setOpeningFloat(openingFloat);
        reconciliation.setSystemCashTotal(cashCollected);
        reconciliation.setCashRefunds(cashRefunds);
        reconciliation.setIncomingHandovers(incomingHandovers);
        reconciliation.setOutgoingHandovers(outgoingHandovers);
        reconciliation.setExpectedCash(expected);
        reconciliation.setPhysicalCashCounted(physical);
        reconciliation.setDifference(difference);
        reconciliation.setRemarks(sanitize(request.getRemarks(), 1000));
        CashReconciliation saved = reconciliationRepository.save(reconciliation);
        auditLogService.success("CASH_RECONCILIATION_CREATE", saved.getId().toString(),
                "Cash drawer reconciliation submitted");
        return toReconciliationView(saved);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getReconciliations(int page, int size) {
        Integer tenantId = requireTenantId();
        User user = requireCollector(tenantId, false);
        Long staffId = canViewAll(user) ? null : user.getId();
        Page<CashReconciliation> records = reconciliationRepository.findTenantHistory(
                tenantId, staffId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.unsorted()));
        Map<String, Object> response = new HashMap<>();
        response.put("content", records.map(this::toReconciliationView).getContent());
        response.put("page", records.getNumber());
        response.put("size", records.getSize());
        response.put("totalPages", records.getTotalPages());
        response.put("totalElements", records.getTotalElements());
        return response;
    }

    private Map<String, Object> collect(Invoice invoice, NonGstInvoice nonGstInvoice,
                                        Integer tenantId, User collector, CollectPaymentRequest request) {
        validateRequest(request);
        String key = request.getIdempotencyKey().trim();
        PaymentRecord existing = paymentRepository.findByTenant_IdAndIdempotencyKey(tenantId, key).orElse(null);
        if (existing != null) {
            if (sameRequest(existing, invoice, nonGstInvoice, request)) {
                return toPaymentView(existing);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was already used");
        }

        BigDecimal total = invoice != null ? invoice.getGrandTotal() : nonGstInvoice.getGrandTotal();
        BigDecimal paid = invoice != null ? invoice.getPaidAmount() : nonGstInvoice.getPaidAmount();
        BigDecimal amount = money(request.getAmount());
        BigDecimal remaining = total.subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(remaining) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment exceeds the remaining invoice balance");
        }

        PaymentRecord record = record(invoice, nonGstInvoice, tenantId, collector, request, "COLLECTION");
        BigDecimal newPaidAmount = paid.add(amount).setScale(2, RoundingMode.HALF_UP);
        if (invoice != null) {
            invoice.setPaidAmount(newPaidAmount);
            invoice.setStatus(newPaidAmount.compareTo(total) >= 0
                    ? Invoice.InvoiceStatus.PAID : Invoice.InvoiceStatus.PENDING);
            invoice.setPaymentStatus(newPaidAmount.compareTo(total) >= 0
                    ? Invoice.PaymentStatus.PAID : Invoice.PaymentStatus.PARTIAL);
            invoiceRepository.save(invoice);
        } else {
            nonGstInvoice.setPaidAmount(newPaidAmount);
            String newStatus = newPaidAmount.compareTo(total) >= 0 ? "PAID" : "PARTIAL";
            nonGstInvoice.setPaymentStatus(newStatus);
            nonGstInvoice.setStatus(newStatus);
            nonGstInvoiceRepository.save(nonGstInvoice);
        }
        auditLogService.success("PAYMENT_RECEIVED", record.getReceiptNo(),
                "Payment collected against invoice " + invoiceNumber(invoice, nonGstInvoice));
        return toPaymentView(record);
    }

    private PaymentRecord record(Invoice invoice, NonGstInvoice nonGstInvoice, Integer tenantId,
                                User collector, CollectPaymentRequest request, String source) {
        PaymentRecord record = new PaymentRecord();
        record.setTenant(tenantReference(tenantId));
        record.setInvoice(invoice);
        record.setNonGstInvoice(nonGstInvoice);
        record.setCollector(collector);
        record.setAmount(money(request.getAmount()));
        record.setMethod(normalizeMethod(request.getMethod()));
        record.setReferenceNo(sanitize(request.getReferenceNo(), 255));
        record.setIdempotencyKey(request.getIdempotencyKey().trim());
        record.setReceiptNo("RCPT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT));
        record.setSource(source);
        record.setCollectedAt(Instant.now());
        return paymentRepository.save(record);
    }

    private boolean sameRequest(PaymentRecord existing, Invoice invoice, NonGstInvoice nonGstInvoice,
                                CollectPaymentRequest request) {
        boolean sameInvoice = invoice != null
                ? existing.getInvoice() != null && existing.getInvoice().getId().equals(invoice.getId())
                : existing.getNonGstInvoice() != null
                    && existing.getNonGstInvoice().getId().equals(nonGstInvoice.getId());
        return sameInvoice
                && existing.getAmount().compareTo(money(request.getAmount())) == 0
                && existing.getMethod().equals(normalizeMethod(request.getMethod()));
    }

    private boolean sameRefund(PaymentRecord existing, PaymentRecord original, RefundPaymentRequest request) {
        return "REFUND".equals(existing.getSource())
                && existing.getReversalOf() != null
                && existing.getReversalOf().getId().equals(original.getId())
                && existing.getAmount().compareTo(money(request.getAmount())) == 0
                && java.util.Objects.equals(existing.getReferenceNo(), sanitize(request.getReason(), 255));
    }

    private void validateRequest(CollectPaymentRequest request) {
        if (request == null || request.getAmount() == null
                || request.getAmount().compareTo(BigDecimal.ZERO) <= 0
                || request.getAmount().scale() > 2
                || request.getAmount().precision() > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment amount must be positive with at most two decimal places");
        }
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()
                || request.getIdempotencyKey().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid idempotency key is required");
        }
        normalizeMethod(request.getMethod());
    }

    private void validateRefundRequest(RefundPaymentRequest request) {
        if (request == null || request.getAmount() == null
                || request.getAmount().compareTo(BigDecimal.ZERO) <= 0
                || request.getAmount().scale() > 2
                || request.getAmount().precision() > 12
                || request.getIdempotencyKey() == null
                || request.getIdempotencyKey().isBlank()
                || request.getIdempotencyKey().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund amount and idempotency key are required");
        }
    }

    private String normalizeMethod(String method) {
        String value = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("CASH")) return "CASH";
        if (value.startsWith("UPI")) return "UPI";
        if (value.startsWith("CARD")) return "CARD";
        if (value.startsWith("BANK")) return "BANK";
        if (value.startsWith("WALLET")) return "WALLET";
        if ("OTHER".equals(value)) return "OTHER";
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payment method");
    }

    private String normalizeOptionalMethod(String method) {
        if (method == null || method.isBlank() || "ALL".equalsIgnoreCase(method.trim())) return null;
        return normalizeMethod(method);
    }

    private void ensureInvoiceCollectible(boolean cancelled) {
        if (cancelled) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot collect payment for a cancelled invoice");
    }

    private Integer requireTenantId() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) throw new AccessDeniedException("Access denied");
        return Math.toIntExact(tenantId);
    }

    private User requireCollector(Integer tenantId, boolean requireCollectPermission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AccessDeniedException("Authentication required");
        }
        User user = userRepository.findByEmail(authentication.getName())
                .or(() -> userRepository.findByUsername(authentication.getName()))
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
        if (user.getTenant() == null || user.getTenant().getId() != tenantId
                || user.isDisabled() || UserRolePolicy.isDisabledRole(user.getRole())) {
            throw new AccessDeniedException("Access denied");
        }
        if (requireCollectPermission && !canCollect(user)) {
            throw new AccessDeniedException("Payment collection is not permitted");
        }
        if (!requireCollectPermission && !canViewCollections(user)) {
            throw new AccessDeniedException("Collection reporting is not permitted");
        }
        return user;
    }

    private User lockCollector(Integer tenantId, User user, boolean requireCollectPermission) {
        User locked = userRepository.findByIdAndTenant_IdForUpdate(user.getId(), tenantId)
                .orElseThrow(() -> new AccessDeniedException("Active tenant member not found"));
        if (locked.isDisabled() || UserRolePolicy.isDisabledRole(locked.getRole())
                || (requireCollectPermission && !canCollect(locked))) {
            throw new AccessDeniedException("Payment collection is not permitted");
        }
        return locked;
    }

    private boolean canCollect(User user) {
        String role = UserRolePolicy.normalize(user.getRole());
        return "store_admin".equals(role) || "billing_manager".equals(role)
                || UserRolePolicy.isSystemAdmin(role);
    }

    private boolean canViewAll(User user) {
        String role = UserRolePolicy.normalize(user.getRole());
        return "store_admin".equals(role) || "billing_manager".equals(role)
                || UserRolePolicy.isSystemAdmin(role);
    }

    private boolean canViewCollections(User user) {
        String role = UserRolePolicy.normalize(user.getRole());
        return canViewAll(user) || "store_billing_viewer".equals(role);
    }

    private Tenant tenantReference(Integer tenantId) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        return tenant;
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private String sanitize(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private String invoiceNumber(Invoice invoice, NonGstInvoice nonGstInvoice) {
        return invoice != null ? invoice.getInvoiceNo() : nonGstInvoice.getInvoiceNo();
    }

    private BigDecimal decimalAt(Object[] values, int index) {
        Object value = values != null && values.length > index ? values[index] : null;
        return value instanceof BigDecimal decimal ? decimal : value instanceof Number number
                ? BigDecimal.valueOf(number.doubleValue()) : ZERO;
    }

    private long longAt(Object[] values, int index) {
        Object value = values != null && values.length > index ? values[index] : null;
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Map<String, Object> toPaymentView(PaymentReportRow row) {
        Map<String, Object> view = new HashMap<>();
        view.put("paymentId", row.getPaymentId());
        view.put("invoiceId", row.getInvoiceId());
        view.put("invoiceNo", row.getInvoiceNo());
        view.put("customerName", row.getCustomerName());
        view.put("amount", row.getAmount());
        view.put("refundableAmount", row.getRefundableAmount());
        view.put("method", row.getMethod());
        view.put("status", row.getStatus());
        view.put("receiptNo", row.getReceiptNo());
        view.put("referenceNo", row.getReferenceNo());
        view.put("collectorName", row.getCollectorName());
        view.put("collectorId", row.getCollectorId());
        view.put("source", row.getSource());
        view.put("collectedAt", row.getCollectedAt());
        return view;
    }

    private Map<String, Object> toPaymentView(PaymentRecord record) {
        Map<String, Object> view = new HashMap<>();
        view.put("paymentId", record.getId());
        view.put("invoiceId", record.getInvoice() != null ? record.getInvoice().getId() : record.getNonGstInvoice().getId());
        view.put("invoiceNo", invoiceNumber(record.getInvoice(), record.getNonGstInvoice()));
        view.put("amount", "REFUND".equals(record.getSource())
                ? record.getAmount().negate() : record.getAmount());
        view.put("method", record.getMethod());
        view.put("status", record.getStatus());
        view.put("receiptNo", record.getReceiptNo());
        view.put("referenceNo", record.getReferenceNo());
        view.put("collectorName", record.getCollector() != null
                ? record.getCollector().getOwnerName() : "Legacy collection (collector unknown)");
        view.put("collectorId", record.getCollector() != null ? record.getCollector().getId() : null);
        view.put("source", record.getSource());
        view.put("collectedAt", record.getCollectedAt());
        return view;
    }

    private Map<String, Object> toReconciliationView(CashReconciliation record) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", record.getId());
        view.put("reconciliationDate", record.getReconciliationDate());
        view.put("collectorId", record.getStaffUser().getId());
        view.put("collectorName", record.getStaffUser().getOwnerName());
        view.put("openingFloat", record.getOpeningFloat());
        view.put("systemCashTotal", record.getSystemCashTotal());
        view.put("expectedCash", record.getExpectedCash());
        view.put("cashRefunds", record.getCashRefunds());
        view.put("incomingHandovers", record.getIncomingHandovers());
        view.put("outgoingHandovers", record.getOutgoingHandovers());
        view.put("physicalCashCounted", record.getPhysicalCashCounted());
        view.put("difference", record.getDifference());
        view.put("status", record.getDifference().signum() == 0 ? "MATCHED"
                : record.getDifference().signum() < 0 ? "SHORTAGE" : "OVERAGE");
        view.put("remarks", record.getRemarks());
        view.put("createdAt", record.getCreatedAt());
        return view;
    }
}
