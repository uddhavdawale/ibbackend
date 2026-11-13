package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.TenantAsset;
import org.ib.ibbackend.entity.TenantAssetType;
import org.ib.ibbackend.repository.TenantAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAssetServiceTest {

    @Test
    void shouldEncryptTenantAssetBeforeStorageAndDecryptForRetrieval() {
        TenantAssetRepository repository = mock(TenantAssetRepository.class);
        TenantDocumentEncryptionService encryptionService = new TenantDocumentEncryptionService(
                Base64.getEncoder().encodeToString(new byte[32]));
        TenantAssetService service = new TenantAssetService(repository, encryptionService);

        Tenant tenant = new Tenant();
        tenant.setId(7);
        byte[] logoContent = {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01
        };
        MockMultipartFile logo = new MockMultipartFile("logo", "logo.png", "image/png", logoContent);
        service.storeIfProvided(tenant, TenantAssetType.LOGO, logo);

        org.mockito.ArgumentCaptor<byte[]> encryptedContentCaptor =
                org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(repository).upsert(
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq("LOGO"),
                org.mockito.ArgumentMatchers.eq("image/png"),
                encryptedContentCaptor.capture());
        TenantAsset storedAsset = new TenantAsset();
        storedAsset.setEncryptedContent(encryptedContentCaptor.getValue());
        assertFalse(java.util.Arrays.equals(logoContent, storedAsset.getEncryptedContent()));
        assertArrayEquals(logoContent, service.decrypt(storedAsset));
    }

    @Test
    void shouldNotStoreMissingOptionalAsset() {
        TenantAssetRepository repository = mock(TenantAssetRepository.class);
        TenantAssetService service = new TenantAssetService(
                repository,
                new TenantDocumentEncryptionService(Base64.getEncoder().encodeToString(new byte[32])));

        Tenant tenant = new Tenant();
        tenant.setId(7);
        service.storeIfProvided(tenant, TenantAssetType.LOGO, null);

        verify(repository, org.mockito.Mockito.never()).upsert(
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                any(byte[].class));
    }
}
