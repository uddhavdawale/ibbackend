package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.InvoiceRequest;
import org.ib.ibbackend.entity.HsnTaxRate;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.HsnTaxRateRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.HsnTaxRateService;
import org.ib.ibbackend.service.InvoiceIdempotencyService;
import org.ib.ibbackend.service.PaymentCollectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvoiceTaxCalculationTest {
    @AfterEach
    void clearRequestContexts() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void extractsInclusiveTaxAndSplitsPaiseExactly() {
        Invoice saved = createInvoice("inclusive", "inclusive", true, "27");
        InvoiceItem item = saved.getItems().get(0);

        assertThat(item.getTaxableAmount()).isEqualByComparingTo("84.75");
        assertThat(item.getTaxAmount()).isEqualByComparingTo("15.25");
        assertThat(item.getCgst()).isEqualByComparingTo("7.63");
        assertThat(item.getSgst()).isEqualByComparingTo("7.62");
        assertThat(item.getIgst()).isEqualByComparingTo("0.00");
        assertThat(item.getCgst().add(item.getSgst()).add(item.getIgst()))
                .isEqualByComparingTo(item.getTaxAmount());
        assertThat(item.getItemTotal()).isEqualByComparingTo("100.00");
        assertThat(item.getPrice()).isEqualByComparingTo("84.75");
        assertThat(item.getEnteredPrice()).isEqualByComparingTo("100.00");
        assertThat(item.getHsn()).isEqualTo("1234");
        assertThat(item.getGstRate()).isEqualByComparingTo("18.00");
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("100.00");
        assertThat(saved.getTaxPricingMode()).isEqualTo("inclusive");
    }

    @Test
    void addsExclusiveTaxToTheCatalogPrice() {
        Invoice saved = createInvoice("exclusive", "exclusive", true, "27");
        InvoiceItem item = saved.getItems().get(0);

        assertThat(item.getTaxableAmount()).isEqualByComparingTo("100.00");
        assertThat(item.getTaxAmount()).isEqualByComparingTo("18.00");
        assertThat(item.getCgst()).isEqualByComparingTo("9.00");
        assertThat(item.getSgst()).isEqualByComparingTo("9.00");
        assertThat(item.getItemTotal()).isEqualByComparingTo("118.00");
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("118.00");
    }

    @Test
    void assignsIgstForInterStateSupply() {
        Invoice saved = createInvoice("exclusive", "exclusive", true, "29");
        InvoiceItem item = saved.getItems().get(0);

        assertThat(item.getCgst()).isEqualByComparingTo("0.00");
        assertThat(item.getSgst()).isEqualByComparingTo("0.00");
        assertThat(item.getIgst()).isEqualByComparingTo("18.00");
        assertThat(item.getIgst()).isEqualByComparingTo(item.getTaxAmount());
    }

    @Test
    void rejectsProductsWithoutAnHsnMasterRate() {
        assertThatThrownBy(() -> createInvoice("exclusive", "exclusive", false, "27"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException responseError = (ResponseStatusException) error;
                    assertThat(responseError.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(responseError.getReason()).contains("Notebook (product 10)", "no applicable GST rate");
                });
    }

    @Test
    void rejectsUnknownPlaceOfSupplyStates() {
        assertThatThrownBy(() -> createInvoice("exclusive", "exclusive", true, "Not a state"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException responseError = (ResponseStatusException) error;
                    assertThat(responseError.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(responseError.getReason()).contains("valid Indian state name or GST state code");
                });
    }

    @Test
    void rejectsItemPricingModeThatConflictsWithInvoiceMode() {
        assertThatThrownBy(() -> createInvoice("inclusive", "exclusive", true, "27"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException responseError = (ResponseStatusException) error;
                    assertThat(responseError.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(responseError.getReason()).contains("must match the invoice taxPricingMode");
                });
    }

    @Test
    void rejectsSellerGstinWithUnknownStateCode() {
        assertThatThrownBy(() -> createInvoice("exclusive", "exclusive", true, "27", "99"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException responseError = (ResponseStatusException) error;
                    assertThat(responseError.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(responseError.getReason()).contains("invalid GST state code");
                });
    }

    @Test
    void replaysGstInvoiceForSameIdempotencyKeyWithoutRepeatingStockLookup() {
        TenantContext.setCurrentTenant(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner@example.com", null, List.of()));

        InvoiceController controller = new InvoiceController();
        ProductRepository productRepository = mock(ProductRepository.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        InvoiceIdempotencyService idempotencyService = mock(InvoiceIdempotencyService.class);
        ReflectionTestUtils.setField(controller, "productRepository", productRepository);
        ReflectionTestUtils.setField(controller, "invoiceRepository", invoiceRepository);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "invoiceIdempotencyService", idempotencyService);

        Tenant tenant = new Tenant();
        tenant.setId(7);
        User user = new User();
        user.setTenant(tenant);
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        Invoice existing = new Invoice();
        existing.setInvoiceNo("INV-EXISTING");
        when(idempotencyService.claimGstInvoice(eq(7L), eq("retry-key"), any()))
                .thenReturn(Optional.of(existing));

        InvoiceRequest request = new InvoiceRequest();
        request.setStatus("PENDING");
        InvoiceItem item = new InvoiceItem();
        item.setProductId(10L);
        item.setQty(1);
        item.setPrice(new BigDecimal("100.00"));
        request.setItems(List.of(item));

        var response = controller.createInvoice(request, "retry-key");

        assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(response.getBody()).isSameAs(existing);
        verify(productRepository, never()).findByIdAndTenantIdForUpdate(any(), any());
        verify(invoiceRepository, never()).save(any(Invoice.class));
    }

    private Invoice createInvoice(String invoiceMode, String itemMode, boolean includeRate, String placeOfSupplyState) {
        return createInvoice(invoiceMode, itemMode, includeRate, placeOfSupplyState, "27");
    }

    private Invoice createInvoice(
            String invoiceMode,
            String itemMode,
            boolean includeRate,
            String placeOfSupplyState,
            String sellerStateCode) {
        TenantContext.setCurrentTenant(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner@example.com", null, List.of()));

        InvoiceController controller = new InvoiceController();
        ProductRepository productRepository = mock(ProductRepository.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        HsnTaxRateRepository rateRepository = mock(HsnTaxRateRepository.class);
        ReflectionTestUtils.setField(controller, "productRepository", productRepository);
        ReflectionTestUtils.setField(controller, "invoiceRepository", invoiceRepository);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "paymentCollectionService", mock(PaymentCollectionService.class));
        ReflectionTestUtils.setField(controller, "hsnTaxRateService", new HsnTaxRateService(rateRepository));

        Tenant tenant = new Tenant();
        tenant.setId(7);
        tenant.setState("Maharashtra");
        tenant.setGstin(sellerStateCode + "ABCDE1234F1Z5");
        User user = new User();
        user.setId(20L);
        user.setEmail("owner@example.com");
        user.setTenant(tenant);
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        Product product = new Product();
        product.setId(10L);
        product.setName("Notebook");
        product.setSku("NOTEBOOK-010");
        product.setHsnSac("1234");
        product.setPrice(new BigDecimal("100.00"));
        product.setQuantity(5);
        product.setTenant(tenant);
        when(productRepository.findByIdAndTenantIdForUpdate(10L, 7L)).thenReturn(Optional.of(product));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        if (includeRate) {
            HsnTaxRate rate = new HsnTaxRate();
            rate.setHsnCode("1234");
            rate.setGstRate(new BigDecimal("18"));
            when(rateRepository.findAllByHsnCodeIn(anyCollection())).thenReturn(List.of(rate));
        } else {
            when(rateRepository.findAllByHsnCodeIn(anyCollection())).thenReturn(List.of());
        }

        InvoiceRequest request = new InvoiceRequest();
        request.setCustomerName("Walk-in");
        request.setStatus("PENDING");
        request.setTaxPricingMode(invoiceMode);
        request.setPlaceOfSupplyState(placeOfSupplyState);
        InvoiceItem submittedItem = new InvoiceItem();
        submittedItem.setProductId(10L);
        submittedItem.setHsn("1234");
        submittedItem.setPrice(new BigDecimal("84.75"));
        submittedItem.setEnteredPrice(new BigDecimal("100.00"));
        submittedItem.setGstRate(new BigDecimal("1"));
        submittedItem.setQty(1);
        submittedItem.setTaxPricingMode(itemMode);
        submittedItem.setTaxableAmount(new BigDecimal("1.00"));
        submittedItem.setTaxAmount(new BigDecimal("1.00"));
        submittedItem.setCgst(new BigDecimal("1.00"));
        submittedItem.setSgst(new BigDecimal("1.00"));
        request.setItems(List.of(submittedItem));

        return (Invoice) controller.createInvoice(request).getBody();
    }
}
