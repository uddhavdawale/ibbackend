package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CustomerProfileRequest;
import org.ib.ibbackend.entity.CustomerProfile;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.NonGstInvoice;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.CustomerProfileRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.NonGstInvoiceRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class CustomerProfileService {

    private final CustomerProfileRepository customerProfileRepository;
    private final InvoiceRepository invoiceRepository;
    private final NonGstInvoiceRepository nonGstInvoiceRepository;

    public CustomerProfileService(CustomerProfileRepository customerProfileRepository,
                                 InvoiceRepository invoiceRepository,
                                 NonGstInvoiceRepository nonGstInvoiceRepository) {
        this.customerProfileRepository = customerProfileRepository;
        this.invoiceRepository = invoiceRepository;
        this.nonGstInvoiceRepository = nonGstInvoiceRepository;
    }

    @Transactional
    public CustomerProfile createOrUpdateCustomer(CustomerProfileRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Customer request is required");
        }

        Long tenantId = request.getTenantId() != null ? request.getTenantId() : TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        String phone = normalizePhone(request.getPhone());
        String email = normalizeEmail(request.getEmail());
        if ((phone == null || phone.isBlank()) && (email == null || email.isBlank())) {
            throw new IllegalArgumentException("Phone or email is required");
        }

        CustomerProfile customer = findCustomerByTenantAndContact(tenantId, phone, email)
                .orElseGet(CustomerProfile::new);

        customer.setTenantId(tenantId);
        customer.setCustomerName(request.getCustomerName() == null || request.getCustomerName().isBlank() ? customer.getCustomerName() : request.getCustomerName());
        customer.setShopName(request.getShopName() == null || request.getShopName().isBlank() ? customer.getShopName() : request.getShopName());
        customer.setAddress(request.getAddress() == null || request.getAddress().isBlank() ? customer.getAddress() : request.getAddress());
        customer.setGstNumber(request.getGstNumber() == null || request.getGstNumber().isBlank() ? customer.getGstNumber() : request.getGstNumber());
        customer.setPhone(phone == null || phone.isBlank() ? customer.getPhone() : phone);
        customer.setEmail(email == null || email.isBlank() ? customer.getEmail() : email);

        return customerProfileRepository.save(customer);
    }

    @Transactional
    public CustomerProfile syncFromInvoice(Invoice invoice) {
        if (invoice == null) {
            return null;
        }

        Long tenantId = invoice.getTenant() != null ? (long) invoice.getTenant().getId() : TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        CustomerProfileRequest request = new CustomerProfileRequest();
        request.setTenantId(tenantId);
        request.setCustomerName(invoice.getCustomerName());
        request.setPhone(invoice.getCustomerPhone());
        request.setEmail(invoice.getCustomerEmail());

        CustomerProfile customer = createOrUpdateCustomer(request);
        List<InvoiceSummary> matchingInvoices = new ArrayList<>();

        // Always include the current invoice snapshot to avoid relying only on follow-up queries.
        addInvoiceIfAbsent(matchingInvoices, new InvoiceSummary(
            invoice.getId(),
            invoice.getInvoiceNo(),
            invoice.getGrandTotal(),
            invoice.getPaidAmount(),
            invoice.getDate()));

        if (customer.getPhone() != null && !customer.getPhone().isBlank()) {
            invoiceRepository.findByTenantIdAndCustomerPhone(tenantId, customer.getPhone())
                .forEach(invoiceItem -> addInvoiceIfAbsent(matchingInvoices, new InvoiceSummary(
                            invoiceItem.getId(),
                            invoiceItem.getInvoiceNo(),
                            invoiceItem.getGrandTotal(),
                            invoiceItem.getPaidAmount(),
                    invoiceItem.getDate())));
        }
        if (customer.getEmail() != null && !customer.getEmail().isBlank()) {
            invoiceRepository.findByTenantIdAndCustomerEmail(tenantId, customer.getEmail())
                .forEach(invoiceItem -> addInvoiceIfAbsent(matchingInvoices, new InvoiceSummary(
                            invoiceItem.getId(),
                            invoiceItem.getInvoiceNo(),
                            invoiceItem.getGrandTotal(),
                            invoiceItem.getPaidAmount(),
                    invoiceItem.getDate())));
        }

        if (nonGstInvoiceRepository != null && customer.getPhone() != null && !customer.getPhone().isBlank()) {
            nonGstInvoiceRepository.findByTenantIdAndCustomerPhone(tenantId, customer.getPhone())
                .forEach(invoiceItem -> addInvoiceIfAbsent(matchingInvoices, new InvoiceSummary(
                                invoiceItem.getId(),
                                invoiceItem.getInvoiceNo(),
                                invoiceItem.getGrandTotal(),
                                invoiceItem.getPaidAmount(),
                    invoiceItem.getDate())));
        }

        BigDecimal dueBalance = BigDecimal.ZERO;
        int count = 0;
        Long lastOrderId = null;
        String lastOrderNo = null;
        LocalDate lastInvoiceDate = null;
        for (InvoiceSummary current : matchingInvoices) {
            if (current == null) {
                continue;
            }
            BigDecimal grandTotal = current.grandTotal() == null ? BigDecimal.ZERO : current.grandTotal();
            BigDecimal paidAmount = current.paidAmount() == null ? BigDecimal.ZERO : current.paidAmount();
            dueBalance = dueBalance.add(grandTotal.subtract(paidAmount).max(BigDecimal.ZERO));
            count++;
            if (current.date() != null && (lastInvoiceDate == null || current.date().isAfter(lastInvoiceDate))) {
                lastInvoiceDate = current.date();
                lastOrderId = current.id();
                lastOrderNo = current.invoiceNo();
            }
        }

        customer.setDueBalance(dueBalance);
        customer.setPreviousOrderCount(count);
        customer.setLastOrderId(lastOrderId);
        customer.setLastOrderNo(lastOrderNo);
        customer.setLastInvoiceDate(lastInvoiceDate);
        return customerProfileRepository.save(customer);
    }

    @Transactional
    public CustomerProfile syncFromNonGstInvoice(NonGstInvoice invoice) {
        if (invoice == null) {
            return null;
        }

        Long tenantId = invoice.getTenant() != null ? (long) invoice.getTenant().getId() : TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        CustomerProfileRequest request = new CustomerProfileRequest();
        request.setTenantId(tenantId);
        request.setCustomerName(invoice.getCustomerName());
        request.setPhone(invoice.getCustomerPhone());

        CustomerProfile customer = createOrUpdateCustomer(request);
        BigDecimal dueBalance = invoice.getGrandTotal() == null ? BigDecimal.ZERO : invoice.getGrandTotal();
        BigDecimal paid = invoice.getPaidAmount() == null ? BigDecimal.ZERO : invoice.getPaidAmount();
        customer.setDueBalance(customer.getDueBalance().add(dueBalance.subtract(paid).max(BigDecimal.ZERO)));
        customer.setPreviousOrderCount(customer.getPreviousOrderCount() + 1);
        customer.setLastOrderId(invoice.getId());
        customer.setLastOrderNo(invoice.getInvoiceNo());
        customer.setLastInvoiceDate(invoice.getDate());
        return customerProfileRepository.save(customer);
    }

    public List<CustomerProfile> listByTenant(Long tenantId) {
        if (tenantId == null) {
            tenantId = TenantContext.getCurrentTenant();
        }
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        return customerProfileRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    public Optional<CustomerProfile> findById(Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        return customerProfileRepository.findById(id)
                .filter(customer -> customer.getTenantId() != null && customer.getTenantId().equals(tenantId));
    }

    public List<CustomerProfile> searchByTenant(Long tenantId, String search) {
        if (tenantId == null) {
            tenantId = TenantContext.getCurrentTenant();
        }
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        String safeSearch = search == null ? "" : search.trim();
        if (safeSearch.isBlank()) {
            return customerProfileRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
        }
        return customerProfileRepository.searchByTenantAndKeyword(tenantId, safeSearch);
    }

    private record InvoiceSummary(Long id, String invoiceNo, BigDecimal grandTotal, BigDecimal paidAmount, LocalDate date) {
    }

    private void addInvoiceIfAbsent(List<InvoiceSummary> target, InvoiceSummary candidate) {
        if (candidate == null) {
            return;
        }

        boolean exists = target.stream().anyMatch(existing -> {
            if (existing == null) {
                return false;
            }
            if (existing.id != null && candidate.id != null) {
                return existing.id.equals(candidate.id);
            }
            return existing.invoiceNo != null
                    && candidate.invoiceNo != null
                    && existing.invoiceNo.equals(candidate.invoiceNo)
                    && existing.date != null
                    && existing.date.equals(candidate.date);
        });

        if (!exists) {
            target.add(candidate);
        }
    }

    private Optional<CustomerProfile> findCustomerByTenantAndContact(Long tenantId, String phone, String email) {
        if (phone != null && !phone.isBlank()) {
            Optional<CustomerProfile> byPhone = customerProfileRepository.findByTenantIdAndPhone(tenantId, phone);
            if (byPhone.isPresent()) {
                return byPhone;
            }
        }
        if (email != null && !email.isBlank()) {
            return customerProfileRepository.findByTenantIdAndEmail(tenantId, email);
        }
        return Optional.empty();
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String normalized = phone.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim();
        return normalized.isBlank() ? null : normalized.toLowerCase();
    }
}
