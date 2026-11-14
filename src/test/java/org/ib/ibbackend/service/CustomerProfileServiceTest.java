package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CustomerProfileRequest;
import org.ib.ibbackend.dto.CustomerInvoiceRecord;
import org.ib.ibbackend.entity.CustomerProfile;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.repository.CustomerProfileRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.NonGstInvoiceRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerProfileServiceTest {

    @Test
    void shouldUpsertCustomerFromInvoiceAndTrackDueBalance() {
        CustomerProfileRepository customerProfileRepository = mock(CustomerProfileRepository.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        NonGstInvoiceRepository nonGstInvoiceRepository = mock(NonGstInvoiceRepository.class);

        CustomerProfile existing = new CustomerProfile();
        existing.setTenantId(7L);
        existing.setCustomerName("Ravi");
        existing.setPhone("9999999999");
        existing.setEmail("ravi@test.com");
        existing.setDueBalance(BigDecimal.ZERO);
        existing.setPreviousOrderCount(1);

        when(customerProfileRepository.findByTenantIdAndPhone(7L, "9999999999")).thenReturn(Optional.of(existing));
        when(invoiceRepository.findByTenantIdAndCustomerPhone(7L, "9999999999")).thenReturn(List.of());
        when(invoiceRepository.findByTenantIdAndCustomerEmail(7L, "ravi@test.com")).thenReturn(List.of());
        when(customerProfileRepository.save(any(CustomerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerProfileService service = new CustomerProfileService(customerProfileRepository, invoiceRepository, nonGstInvoiceRepository);

        Invoice invoice = new Invoice();
        invoice.setId(110L);
        invoice.setInvoiceNo("INV-2026/0001");
        invoice.setCustomerName("Ravi");
        invoice.setCustomerPhone("9999999999");
        invoice.setCustomerEmail("ravi@test.com");
        invoice.setDate(LocalDate.now());
        invoice.setGrandTotal(new BigDecimal("500"));
        invoice.setPaidAmount(new BigDecimal("200"));

        Tenant tenant = new Tenant();
        tenant.setId(7);
        invoice.setTenant(tenant);

        CustomerProfile saved = service.syncFromInvoice(invoice);

        assertNotNull(saved);
        assertEquals("Ravi", saved.getCustomerName());
        assertEquals(0, new BigDecimal("300.00").compareTo(saved.getDueBalance()));
        assertEquals(110L, saved.getLastOrderId());
        assertEquals("INV-2026/0001", saved.getLastOrderNo());
    }

    @Test
    void shouldNotUseShopOwnerEmailAsInvoiceCustomerEmail() {
        CustomerProfileRepository customerProfileRepository = mock(CustomerProfileRepository.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        NonGstInvoiceRepository nonGstInvoiceRepository = mock(NonGstInvoiceRepository.class);
        when(customerProfileRepository.save(any(CustomerProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CustomerProfileService service = new CustomerProfileService(
                customerProfileRepository, invoiceRepository, nonGstInvoiceRepository);
        Invoice invoice = new Invoice();
        invoice.setCustomerName("Mina");
        invoice.setCustomerEmail("shop-owner@example.com");
        invoice.setGrandTotal(new BigDecimal("100.00"));
        invoice.setPaidAmount(BigDecimal.ZERO);
        Tenant tenant = new Tenant();
        tenant.setId(7);
        invoice.setTenant(tenant);

        CustomerProfile saved = service.syncFromInvoice(invoice);

        assertEquals("Mina", saved.getCustomerName());
        assertNull(saved.getEmail());
    }

    @Test
    void shouldKeepPreviousOutstandingBalanceWhenAddingNewInvoiceDue() {
        CustomerProfileRepository customerProfileRepository = mock(CustomerProfileRepository.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        NonGstInvoiceRepository nonGstInvoiceRepository = mock(NonGstInvoiceRepository.class);

        CustomerProfile existing = new CustomerProfile();
        existing.setTenantId(12L);
        existing.setCustomerName("Sandip");
        existing.setPhone("9876543210");
        existing.setDueBalance(new BigDecimal("11599.02"));
        existing.setBalanceDue(new BigDecimal("11599.02"));
        existing.setOutstandingBalance(new BigDecimal("11599.02"));

        when(customerProfileRepository.findByTenantIdAndPhone(12L, "9876543210")).thenReturn(Optional.of(existing));
        when(customerProfileRepository.save(any(CustomerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerProfileService service = new CustomerProfileService(customerProfileRepository, invoiceRepository, nonGstInvoiceRepository);

        Invoice invoice = new Invoice();
        invoice.setId(500L);
        invoice.setInvoiceNo("INV-2026/1001");
        invoice.setCustomerName("Sandip");
        invoice.setCustomerPhone("9876543210");
        invoice.setDate(LocalDate.now());
        invoice.setGrandTotal(new BigDecimal("21599.02"));
        invoice.setPaidAmount(new BigDecimal("10000.00"));
        Tenant tenant = new Tenant();
        tenant.setId(12);
        invoice.setTenant(tenant);

        CustomerProfile updated = service.syncFromInvoice(invoice);

        assertNotNull(updated);
        assertEquals(new BigDecimal("11599.02").add(new BigDecimal("11599.02")), updated.getOutstandingBalance());
        assertEquals(new BigDecimal("11599.02").add(new BigDecimal("11599.02")), updated.getDueBalance());
        assertEquals(updated.getDueBalance(), updated.getBalanceDue());
        assertEquals(updated.getDueBalance(), updated.getOutstandingBalance());
    }

    @Test
    void shouldCreateCustomerProfileFromRequest() {
        CustomerProfileRepository repository = mock(CustomerProfileRepository.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        NonGstInvoiceRepository nonGstInvoiceRepository = mock(NonGstInvoiceRepository.class);

        when(repository.save(any(CustomerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerProfileService service = new CustomerProfileService(repository, invoiceRepository, nonGstInvoiceRepository);
        CustomerProfileRequest request = new CustomerProfileRequest();
        request.setTenantId(5L);
        request.setCustomerName("Aditi");
        request.setPhone("8888888888");
        request.setEmail("aditi@test.com");
        request.setAddress("MG Road");
        request.setShopName("Aditi Mart");
        request.setGstNumber("27ABCDE1234F1Z5");

        CustomerProfile profile = service.createOrUpdateCustomer(request);

        assertNotNull(profile);
        assertEquals("Aditi", profile.getCustomerName());
        assertEquals("Aditi Mart", profile.getShopName());
        assertEquals("27ABCDE1234F1Z5", profile.getGstNumber());
    }

    @Test
    void shouldListAllCustomersForRequestedTenant() {
        CustomerProfileRepository repository = mock(CustomerProfileRepository.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        NonGstInvoiceRepository nonGstInvoiceRepository = mock(NonGstInvoiceRepository.class);
        CustomerProfile first = new CustomerProfile();
        CustomerProfile second = new CustomerProfile();
        when(repository.findByTenantIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of(first, second));

        CustomerProfileService service = new CustomerProfileService(repository, invoiceRepository, nonGstInvoiceRepository);

        List<CustomerProfile> customers = service.listByTenant(7L);

        assertEquals(List.of(first, second), customers);
        verify(repository).findByTenantIdOrderByUpdatedAtDesc(7L);
    }

    @Test
    void shouldIncludeInvoiceCustomersMissingFromProfilesForTenant() {
        CustomerProfileRepository repository = mock(CustomerProfileRepository.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        NonGstInvoiceRepository nonGstInvoiceRepository = mock(NonGstInvoiceRepository.class);
        CustomerProfile existing = new CustomerProfile();
        existing.setTenantId(7L);
        existing.setCustomerName("Ravi");
        existing.setPhone("9999999999");
        existing.setId(1L);
        LocalDate firstOrderDate = LocalDate.of(2026, 11, 10);
        LocalDate lastOrderDate = LocalDate.of(2026, 11, 11);

        when(repository.findByTenantIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of(existing));
        when(invoiceRepository.findCustomerRecordsByTenantId(7L)).thenReturn(List.of(
                new CustomerInvoiceRecord(10L, "INV-10", "Mina", "8888888888", "mina@example.com",
                        new BigDecimal("100.00"), new BigDecimal("40.00"), firstOrderDate),
                new CustomerInvoiceRecord(11L, "INV-11", "Mina", "8888888888", "mina@example.com",
                        new BigDecimal("50.00"), new BigDecimal("50.00"), lastOrderDate)
        ));
        when(nonGstInvoiceRepository.findCustomerRecordsByTenantId(7L)).thenReturn(List.of(
                new CustomerInvoiceRecord(3L, "NG-3", "Asha", "7777777777",
                        new BigDecimal("20.00"), BigDecimal.ZERO, lastOrderDate)
        ));

        CustomerProfileService service = new CustomerProfileService(repository, invoiceRepository, nonGstInvoiceRepository);

        List<CustomerProfile> customers = service.listByTenant(7L);

        assertEquals(3, customers.size());
        CustomerProfile mina = customers.stream()
                .filter(customer -> "Mina".equals(customer.getCustomerName()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, new BigDecimal("60.00").compareTo(mina.getDueBalance()));
        assertEquals(2, mina.getPreviousOrderCount());
        assertEquals(11L, mina.getLastOrderId());
        assertEquals(lastOrderDate, mina.getLastInvoiceDate());
        verify(invoiceRepository).findCustomerRecordsByTenantId(7L);
        verify(nonGstInvoiceRepository).findCustomerRecordsByTenantId(7L);
    }

    @Test
    void shouldKeepInvoiceCustomersDistinctWhenTheirInvoicesShareAnEmail() {
        CustomerProfileRepository repository = mock(CustomerProfileRepository.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        NonGstInvoiceRepository nonGstInvoiceRepository = mock(NonGstInvoiceRepository.class);
        when(repository.findByTenantIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of());
        when(invoiceRepository.findCustomerRecordsByTenantId(7L)).thenReturn(List.of(
                new CustomerInvoiceRecord(10L, "INV-10", "Mina", null, "shop-owner@example.com",
                        new BigDecimal("100.00"), BigDecimal.ZERO, LocalDate.of(2026, 11, 10)),
                new CustomerInvoiceRecord(11L, "INV-11", "Asha", null, "shop-owner@example.com",
                        new BigDecimal("50.00"), BigDecimal.ZERO, LocalDate.of(2026, 11, 11))
        ));
        when(nonGstInvoiceRepository.findCustomerRecordsByTenantId(7L)).thenReturn(List.of());

        CustomerProfileService service = new CustomerProfileService(repository, invoiceRepository, nonGstInvoiceRepository);

        List<CustomerProfile> customers = service.listByTenant(7L);

        assertEquals(2, customers.size());
        assertEquals(List.of("Asha", "Mina"), customers.stream()
                .map(CustomerProfile::getCustomerName)
                .sorted()
                .toList());
    }
}
