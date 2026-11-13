package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CustomerProfileRequest;
import org.ib.ibbackend.dto.CustomerInvoiceRecord;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        String customerName = normalizeCustomerName(request.getCustomerName());
        if ((phone == null || phone.isBlank()) && (email == null || email.isBlank()) && (customerName == null || customerName.isBlank())) {
            throw new IllegalArgumentException("Phone, email, or customer name is required");
        }

        CustomerProfile customer = findCustomerByTenantAndContact(tenantId, phone, email, customerName)
                .orElseGet(CustomerProfile::new);

        customer.setTenantId(tenantId);
        customer.setCustomerName(customerName == null || customerName.isBlank() ? customer.getCustomerName() : customerName);
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
        BigDecimal previousBalance = customer.getDueBalance() == null ? BigDecimal.ZERO : customer.getDueBalance();
        BigDecimal currentInvoiceDue = computeInvoiceDue(invoice.getGrandTotal(), invoice.getPaidAmount());
        BigDecimal totalDue = previousBalance.add(currentInvoiceDue).max(BigDecimal.ZERO);

        customer.applyOutstandingAmount(totalDue);
        customer.setPreviousOrderCount(customer.getPreviousOrderCount() + 1);
        customer.setLastOrderId(invoice.getId());
        customer.setLastOrderNo(invoice.getInvoiceNo());
        customer.setLastInvoiceDate(invoice.getDate());
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
        BigDecimal previousBalance = customer.getDueBalance() == null ? BigDecimal.ZERO : customer.getDueBalance();
        BigDecimal currentInvoiceDue = computeInvoiceDue(invoice.getGrandTotal(), invoice.getPaidAmount());
        BigDecimal totalDue = previousBalance.add(currentInvoiceDue).max(BigDecimal.ZERO);

        customer.applyOutstandingAmount(totalDue);
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

        List<CustomerProfile> customers = new ArrayList<>(customerProfileRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId));
        for (CustomerInvoiceRecord invoice : invoiceRepository.findCustomerRecordsByTenantId(tenantId)) {
            addInvoiceCustomer(customers, tenantId, invoice);
        }
        for (CustomerInvoiceRecord invoice : nonGstInvoiceRepository.findCustomerRecordsByTenantId(tenantId)) {
            addInvoiceCustomer(customers, tenantId, invoice);
        }
        customers.sort(Comparator.comparing(
                CustomerProfile::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())
        ));
        return customers;
    }

    private void addInvoiceCustomer(List<CustomerProfile> customers, Long tenantId, CustomerInvoiceRecord invoice) {
        String phone = normalizePhone(invoice.customerPhone());
        String email = normalizeEmail(invoice.customerEmail());
        String name = normalizeCustomerName(invoice.customerName());
        if ((phone == null || phone.isBlank()) && (email == null || email.isBlank()) && (name == null || name.isBlank())) {
            return;
        }

        CustomerProfile customer = findCustomerInList(customers, tenantId, phone, email, name);
        if (customer == null) {
            customer = new CustomerProfile();
            customer.setTenantId(tenantId);
            customer.setCustomerName(name);
            customer.setPhone(phone);
            customer.setEmail(email);
            customers.add(customer);
        }

        if (customer.getId() != null) {
            return;
        }

        BigDecimal currentDue = customer.getDueBalance();
        customer.applyOutstandingAmount(currentDue.add(computeInvoiceDue(invoice.grandTotal(), invoice.paidAmount())));
        customer.setPreviousOrderCount(customer.getPreviousOrderCount() + 1);
        if (invoice.date() != null
                && (customer.getLastInvoiceDate() == null || !invoice.date().isBefore(customer.getLastInvoiceDate()))) {
            customer.setLastOrderId(invoice.id());
            customer.setLastOrderNo(invoice.invoiceNo());
            customer.setLastInvoiceDate(invoice.date());
            customer.setUpdatedAt(invoice.date().atStartOfDay());
        }
        if (invoice.date() != null
                && (customer.getCreatedAt() == null || invoice.date().isBefore(customer.getCreatedAt().toLocalDate()))) {
            customer.setCreatedAt(invoice.date().atStartOfDay());
        }
    }

    private CustomerProfile findCustomerInList(
            List<CustomerProfile> customers,
            Long tenantId,
            String phone,
            String email,
            String name
    ) {
        for (CustomerProfile customer : customers) {
            if (customer.getTenantId() == null || !customer.getTenantId().equals(tenantId)) {
                continue;
            }
            if (phone != null && phone.equals(normalizePhone(customer.getPhone()))) {
                return customer;
            }
        }
        for (CustomerProfile customer : customers) {
            if (customer.getTenantId() == null || !customer.getTenantId().equals(tenantId)) {
                continue;
            }
            if (email != null && email.equals(normalizeEmail(customer.getEmail()))) {
                return customer;
            }
        }
        for (CustomerProfile customer : customers) {
            if (customer.getTenantId() == null || !customer.getTenantId().equals(tenantId)) {
                continue;
            }
            if (name != null && name.equals(normalizeCustomerName(customer.getCustomerName()))) {
                return customer;
            }
        }
        return null;
    }

    public Optional<CustomerProfile> findById(Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        return customerProfileRepository.findById(id)
                .filter(customer -> customer.getTenantId() != null && customer.getTenantId().equals(tenantId));
    }

    public Optional<CustomerProfile> findByPhone(Long tenantId, String phone) {
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        String normalizedPhone = normalizePhone(phone);
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            return Optional.empty();
        }
        return customerProfileRepository.findByTenantIdAndPhone(tenantId, normalizedPhone);
    }

    @Transactional
    public CustomerProfile updateCustomerProfile(Long id, CustomerProfileRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Customer request is required");
        }

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        CustomerProfile customer = customerProfileRepository.findById(id)
                .filter(existing -> existing.getTenantId() != null && existing.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Customer profile not found for this tenant"));

        if (request.getCustomerName() != null && !request.getCustomerName().isBlank()) {
            customer.setCustomerName(request.getCustomerName());
        }
        if (request.getShopName() != null) {
            customer.setShopName(request.getShopName());
        }
        if (request.getAddress() != null) {
            customer.setAddress(request.getAddress());
        }
        if (request.getGstNumber() != null) {
            customer.setGstNumber(request.getGstNumber());
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            customer.setPhone(normalizePhone(request.getPhone()));
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            customer.setEmail(normalizeEmail(request.getEmail()));
        }
        return customerProfileRepository.save(customer);
    }

    public List<CustomerProfile> searchByTenant(Long tenantId, String search) {
        if (tenantId == null) {
            tenantId = TenantContext.getCurrentTenant();
        }
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        List<CustomerProfile> customers = listByTenant(tenantId);
        String safeSearch = search == null ? "" : search.trim();
        if (safeSearch.isBlank()) {
            return customers;
        }
        String normalizedSearch = safeSearch.toLowerCase(Locale.ROOT);
        return customers.stream()
                .filter(customer -> containsIgnoreCase(customer.getCustomerName(), normalizedSearch)
                        || containsIgnoreCase(customer.getPhone(), normalizedSearch)
                        || containsIgnoreCase(customer.getEmail(), normalizedSearch)
                        || containsIgnoreCase(customer.getShopName(), normalizedSearch))
                .toList();
    }

    private boolean containsIgnoreCase(String value, String normalizedSearch) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedSearch);
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

    private Optional<CustomerProfile> findCustomerByTenantAndContact(Long tenantId, String phone, String email, String customerName) {
        if (phone != null && !phone.isBlank()) {
            Optional<CustomerProfile> byPhone = customerProfileRepository.findByTenantIdAndPhone(tenantId, phone);
            if (byPhone.isPresent()) {
                return byPhone;
            }
        }
        if (email != null && !email.isBlank()) {
            Optional<CustomerProfile> byEmail = customerProfileRepository.findByTenantIdAndEmail(tenantId, email);
            if (byEmail.isPresent()) {
                return byEmail;
            }
        }
        if (customerName != null && !customerName.isBlank()) {
            return customerProfileRepository.findByTenantIdAndCustomerName(tenantId, customerName);
        }
        return Optional.empty();
    }

    private BigDecimal computeInvoiceDue(BigDecimal grandTotal, BigDecimal receivedAmount) {
        BigDecimal total = grandTotal == null ? BigDecimal.ZERO : grandTotal;
        BigDecimal received = receivedAmount == null ? BigDecimal.ZERO : receivedAmount.max(BigDecimal.ZERO);
        return total.subtract(received).max(BigDecimal.ZERO);
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String normalized = phone.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeCustomerName(String customerName) {
        if (customerName == null) {
            return null;
        }
        String normalized = customerName.trim();
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
