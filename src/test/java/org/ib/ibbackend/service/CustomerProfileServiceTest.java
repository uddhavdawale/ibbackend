package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CustomerProfileRequest;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}
