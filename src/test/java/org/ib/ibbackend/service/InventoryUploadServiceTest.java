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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
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

        when(productRepository.findBySku(any())).thenReturn(Optional.empty());
        when(productRepository.save(any(org.ib.ibbackend.entity.Product.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "inventory.csv",
                "text/csv",
            ("Item,HSN/SAC,MRP/ Item,QTY,Set,Price/QTY,PO Number,Min Stock,Category\n" +
             "Rice,RICE-001,250,20,Pack of 6,250,PO-1001,5,Groceries\n" +
             "Soap,SOAP-003,80,50,1,80,PO-1002,15,Personal Care\n").getBytes()
        );

        var result = service.processUpload(file, "inventory-upload", null);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getCreatedCount());
        assertEquals(0, result.getUpdatedCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals("RICE-001", String.valueOf(result.getRecords().get(0).get("hsnSac")));
        assertEquals("PO-1001", String.valueOf(result.getRecords().get(0).get("poNumber")));
        assertEquals("Pack of 6", result.getRecords().get(0).get("set"));
    }

    @Test
    void shouldProcessCsvUploadUsingBulkOrderTemplateHeaders() {
        ProductRepository productRepository = mock(ProductRepository.class);
        InventoryUploadService service = new InventoryUploadService(productRepository);

        when(productRepository.findBySku(any())).thenReturn(Optional.empty());
        when(productRepository.save(any(org.ib.ibbackend.entity.Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "bulk-order.csv",
                "text/csv",
            ("Item,HSN/SAC,MRP/ Item,QTY,Set,Price/QTY,PO Number,Min Stock,Category\n" +
             "iphone,785693254,110000,10,2,98000,202601,2,Electronics\n" +
             "TV LG,785693255,13000,5,1,130000,202601,1,Electronics\n").getBytes()
        );

        var result = service.processUpload(file, "inventory-upload", null);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getCreatedCount());
        assertEquals(0, result.getUpdatedCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals("785693254", String.valueOf(result.getRecords().get(0).get("sku")));
        assertEquals("110000", String.valueOf(result.getRecords().get(0).get("mrp")));
        assertEquals("98000", String.valueOf(result.getRecords().get(0).get("pricePerQty")));
    }

    @Test
    void shouldAcceptDecimalAndStringSetValuesDuringUpload() {
        ProductRepository productRepository = mock(ProductRepository.class);
        InventoryUploadService service = new InventoryUploadService(productRepository);

        when(productRepository.findBySku(any())).thenReturn(Optional.empty());
        when(productRepository.save(any(org.ib.ibbackend.entity.Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.processUpload(
                null,
                null,
                "[{\"product\":\"Rice\",\"sku\":\"RICE-001\",\"hsnSac\":\"RICE-001\",\"qty\":\"5\",\"minStock\":\"2\",\"set\":\"2 pcs\",\"price\":\"100.00\",\"mrp\":\"120.00\",\"category\":\"Groceries\"}]",
                9L,
                22L
        );

        assertTrue(result.isSuccess());
        assertEquals(5, result.getRecords().get(0).get("qty"));
        assertEquals("2 pcs", result.getRecords().get(0).get("set"));
        assertEquals(new BigDecimal("120.00"), result.getRecords().get(0).get("mrp"));
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
    void shouldUpdateExistingSkuWithinTheSameTenantAndAllowOtherTenants() {
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

        org.ib.ibbackend.entity.Product existing = new org.ib.ibbackend.entity.Product();
        existing.setId(100L);
        existing.setName("Rice");
        existing.setSku("RICE-001");
        existing.setHsnSac("RICE-001");
        existing.setPrice(new BigDecimal("100.00"));
        existing.setMrp(new BigDecimal("100.00"));
        existing.setSetSize("1");
        existing.setPoNumber("PO-1001");
        existing.setCategory("Groceries");
        existing.setQuantity(3);
        existing.setTenant(tenant);

        when(userRepository.findByEmail("tenantuser@example.com")).thenReturn(java.util.Optional.of(user));
        when(productRepository.findAllBySkuAndTenantId("RICE-001", 9L)).thenReturn(java.util.List.of(existing));
        when(productRepository.findAllBySkuAndTenantId("RICE-001", 10L)).thenReturn(java.util.Collections.emptyList());
        when(productRepository.save(org.mockito.ArgumentMatchers.any(org.ib.ibbackend.entity.Product.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        InventoryUploadService service = new InventoryUploadService(productRepository, userRepository);

        var sameTenantResult = service.processUpload(
            null,
            null,
            "[{\"product\":\"Rice\",\"sku\":\"RICE-001\",\"hsnSac\":\"RICE-001\",\"qty\":5,\"minStock\":2,\"set\":1,\"price\":100,\"pricePerQty\":100,\"mrp\":100,\"category\":\"Groceries\"}]",
            9L,
            22L
        );

        assertTrue(sameTenantResult.isSuccess());
        assertEquals(0, sameTenantResult.getCreatedCount());
        assertEquals(1, sameTenantResult.getUpdatedCount());

        Tenant tenant10 = new Tenant();
        tenant10.setId(10);

        User user10 = new User();
        user10.setId(30L);
        user10.setEmail("tenant10@example.com");
        user10.setTenant(tenant10);

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("tenant10@example.com", null)
        );
        TenantContext.setCurrentTenant(10L);
        when(userRepository.findByEmail("tenant10@example.com")).thenReturn(java.util.Optional.of(user10));

        var otherTenantResult = service.processUpload(
            null,
            null,
            "[{\"product\":\"Rice\",\"sku\":\"RICE-001\",\"hsnSac\":\"RICE-001\",\"qty\":7,\"minStock\":2,\"set\":1,\"price\":100,\"pricePerQty\":100,\"mrp\":100,\"category\":\"Groceries\"}]",
            10L,
            30L
        );

        assertTrue(otherTenantResult.isSuccess());
        assertEquals(1, otherTenantResult.getCreatedCount());
        assertEquals(0, otherTenantResult.getUpdatedCount());
    }

    @Test
    void shouldCreateSeparateProductVariantWhenPoNumberDiffersForSameSku() {
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

        org.ib.ibbackend.entity.Product existing = new org.ib.ibbackend.entity.Product();
        existing.setId(100L);
        existing.setName("Rice");
        existing.setSku("RICE-001");
        existing.setHsnSac("RICE-001");
        existing.setPoNumber("PO-1001");
        existing.setPrice(new BigDecimal("100.00"));
        existing.setMrp(new BigDecimal("130.00"));
        existing.setSetSize("1");
        existing.setCategory("Groceries");
        existing.setQuantity(10);
        existing.setTenant(tenant);

        when(userRepository.findByEmail("tenantuser@example.com")).thenReturn(java.util.Optional.of(user));
        when(productRepository.findAllBySkuAndTenantId("RICE-001", 9L)).thenReturn(java.util.List.of(existing));
        when(productRepository.save(org.mockito.ArgumentMatchers.any(org.ib.ibbackend.entity.Product.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        InventoryUploadService service = new InventoryUploadService(productRepository, userRepository);

        var result = service.processUpload(
            null,
            null,
            "[{\"product\":\"Rice\",\"sku\":\"RICE-001\",\"hsnSac\":\"RICE-001\",\"qty\":5,\"minStock\":2,\"set\":1,\"price\":120,\"pricePerQty\":120,\"mrp\":130,\"poNumber\":\"PO-1002\",\"category\":\"Groceries\"}]",
            9L,
            22L
        );

        assertTrue(result.isSuccess());
        assertEquals(1, result.getCreatedCount());
        assertEquals(0, result.getUpdatedCount());
        assertEquals(10, existing.getQuantity());
        assertEquals("PO-1001", existing.getPoNumber());
    }

    @Test
    void shouldUpdateExistingQuantityWhenOnlyPoNumberDiffersForSameItem() {
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

        org.ib.ibbackend.entity.Product existing = new org.ib.ibbackend.entity.Product();
        existing.setId(100L);
        existing.setSku("RICE-001");
        existing.setName("Rice");
        existing.setHsnSac("RICE-001");
        existing.setPrice(new BigDecimal("100.00"));
        existing.setMrp(new BigDecimal("130.00"));
        existing.setSetSize("1");
        existing.setPoNumber("PO-1001");
        existing.setCategory("Groceries");
        existing.setQuantity(10);
        existing.setTenant(tenant);

        when(userRepository.findByEmail("tenantuser@example.com")).thenReturn(java.util.Optional.of(user));
        when(productRepository.findAllBySkuAndTenantId("RICE-001", 9L)).thenReturn(java.util.List.of(existing));
        when(productRepository.findBySkuAndPoNumberAndTenantId("RICE-001", "PO-1002", 9L)).thenReturn(Optional.empty());
        when(productRepository.save(org.mockito.ArgumentMatchers.any(org.ib.ibbackend.entity.Product.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        InventoryUploadService service = new InventoryUploadService(productRepository, userRepository);

        var result = service.processUpload(
            null,
            null,
            "[{\"product\":\"Rice\",\"sku\":\"RICE-001\",\"hsnSac\":\"RICE-001\",\"qty\":5,\"minStock\":2,\"set\":1,\"price\":100,\"pricePerQty\":100,\"mrp\":130,\"poNumber\":\"PO-1002\",\"category\":\"Groceries\"}]",
            9L,
            22L
        );

        assertTrue(result.isSuccess());
        assertEquals(0, result.getCreatedCount());
        assertEquals(1, result.getUpdatedCount());
        assertEquals(15, existing.getQuantity());
        assertEquals("PO-1002", existing.getPoNumber());
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
