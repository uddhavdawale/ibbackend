package org.ib.ibbackend.service;

import org.ib.ibbackend.exception.ValidationException;
import org.ib.ibbackend.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class InventoryUploadServiceTest {

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
}
