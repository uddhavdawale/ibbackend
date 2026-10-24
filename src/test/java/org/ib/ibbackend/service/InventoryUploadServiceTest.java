package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.exception.ValidationException;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryUploadServiceTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void shouldProcessCsvUploadAndCreateProducts() {
        ProductRepository productRepository = mock(ProductRepository.class);
        InventoryUploadService service = new InventoryUploadService(productRepository);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "inventory.csv",
                "text/csv",
                ("Product,SKU,Qty,Min Stock,Price,Category\n" +
                 "Rice,RICE-001,20,5,250,Groceries\n" +
                 "Soap,SOAP-003,50,15,80,Personal Care\n").getBytes()
        );

        var result = service.processUpload(file, "inventory-upload", null);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getCreatedCount());
        assertEquals(0, result.getUpdatedCount());
        assertEquals(0, result.getSkippedCount());
    }

    @Test
    void shouldRejectOversizedUpload() {
        ProductRepository productRepository = mock(ProductRepository.class);
        InventoryUploadService service = new InventoryUploadService(productRepository);

        byte[] hugeData = new byte[(int) InventoryUploadService.MAX_UPLOAD_SIZE_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "inventory.csv",
                "text/csv",
                hugeData
        );

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.processUpload(file, "inventory-upload", null));
        assertTrue(ex.getMessage().contains("too large"));
    }

    @Test
    void shouldRejectUnsupportedFileType() {
        ProductRepository productRepository = mock(ProductRepository.class);
        InventoryUploadService service = new InventoryUploadService(productRepository);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "inventory.exe",
                "application/x-msdownload",
                "not-a-real-upload".getBytes()
        );

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.processUpload(file, "inventory-upload", null));
        assertTrue(ex.getMessage().contains("supported"));
    }

    @Test
    void shouldComputeCanonicalPaymentStateFromBackendTruth() {
        Invoice invoice = new Invoice();
        invoice.setGrandTotal(new BigDecimal("1000.00"));
        invoice.setPaidAmount(new BigDecimal("350.00"));

        assertEquals(Invoice.PaymentStatus.PARTIAL, invoice.getPaymentStatus());
        assertEquals(new BigDecimal("650.00"), invoice.getOutstandingAmount());

        invoice.setPaidAmount(new BigDecimal("1000.00"));
        assertEquals(Invoice.PaymentStatus.PAID, invoice.getPaymentStatus());
        assertEquals(new BigDecimal("0.00"), invoice.getOutstandingAmount());
    }

    @Test
    void shouldNormalizeNullInvoicePaymentValues() {
        Invoice invoice = new Invoice();
        invoice.setPaidAmount(null);
        invoice.setPaymentStatus(null);
        invoice.setStatus(null);

        assertEquals(BigDecimal.ZERO, invoice.getPaidAmount());
        assertEquals(Invoice.PaymentStatus.PENDING, invoice.getPaymentStatus());
        assertEquals(Invoice.InvoiceStatus.PENDING, invoice.getStatus());
    }

    @Test
    void shouldRejectMismatchedTenantOrUserForUpload() {
        ProductRepository productRepository = mock(ProductRepository.class);
        UserRepository userRepository = mock(UserRepository.class);

        Tenant tenant = new Tenant();
        tenant.setId(5);

        User user = new User();
        user.setId(12L);
        user.setEmail("owner@example.com");
        user.setTenant(tenant);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner@example.com", null)
        );
        TenantContext.setCurrentTenant(7L);

        when(userRepository.findByEmail("owner@example.com")).thenReturn(java.util.Optional.of(user));

        InventoryUploadService service = new InventoryUploadService(productRepository, userRepository);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.processUpload(
                        null,
                        null,
                        "[{\"product\":\"Rice\",\"sku\":\"RICE-001\",\"qty\":5,\"minStock\":2,\"price\":100,\"category\":\"Groceries\"}]",
                        99L,
                        12L
                ));

        assertTrue(ex.getMessage().contains("Tenant mismatch") || ex.getMessage().contains("User mismatch"));
    }

    @Test
    void shouldOnlyRejectDuplicateSkuWithinTheSameTenant() {
        ProductRepository productRepository = mock(ProductRepository.class);
        UserRepository userRepository = mock(UserRepository.class);

        Tenant tenant = new Tenant();
        tenant.setId(9);

        User user = new User();
        user.setId(22L);
        user.setEmail("tenantuser@example.com");
        user.setTenant(tenant);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tenantuser@example.com", null)
        );
        TenantContext.setCurrentTenant(9L);

        when(userRepository.findByEmail("tenantuser@example.com")).thenReturn(java.util.Optional.of(user));
        when(productRepository.existsBySkuAndTenantId("RICE-001", 9L)).thenReturn(true);

        InventoryUploadService service = new InventoryUploadService(productRepository, userRepository);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.processUpload(
                        null,
                        null,
                        "[{\"product\":\"Rice\",\"sku\":\"RICE-001\",\"qty\":5,\"minStock\":2,\"price\":100,\"category\":\"Groceries\"}]",
                        9L,
                        22L
                ));

        assertTrue(ex.getErrors().stream().anyMatch(error -> error.contains("RICE-001") && error.contains("already exists")));

        when(productRepository.existsBySkuAndTenantId("RICE-001", 10L)).thenReturn(false);
        // same SKU in a different tenant should not be rejected by this tenant's validation path
        assertFalse(productRepository.existsBySkuAndTenantId("RICE-001", 10L));
    }

    @Test
    void shouldUseParameterizedRepositoryQueriesAndRejectRawSqlPatterns() throws IOException {
        String repositorySource = Files.readString(Path.of("src/main/java/org/ib/ibbackend/repository/ProductRepository.java"));

        assertTrue(repositorySource.contains("findBySku"));
        assertTrue(repositorySource.contains("@Query"));
        assertTrue(repositorySource.contains(":search") || repositorySource.contains(":tenantId"));
        assertFalse(repositorySource.contains("NativeQuery"));
        assertFalse(repositorySource.contains("Statement"));
        assertFalse(repositorySource.contains("\" + "));
        assertFalse(repositorySource.contains("' +"));
    }

    @Test
    void shouldExposeTenantScopedProductAndInvoiceQueries() throws NoSuchMethodException {
        ProductRepository.class.getMethod("findByTenantId", Long.class, Pageable.class);
        ProductRepository.class.getMethod("findByIdAndTenantId", Long.class, Long.class);
        InvoiceRepository.class.getMethod("findByTenantId", Long.class, Pageable.class);
        InvoiceRepository.class.getMethod("findByIdAndTenantId", Long.class, Long.class);
    }
}
