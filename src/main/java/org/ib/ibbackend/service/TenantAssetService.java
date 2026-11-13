package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.TenantAsset;
import org.ib.ibbackend.entity.TenantAssetType;
import org.ib.ibbackend.repository.TenantAssetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Optional;

@Service
public class TenantAssetService {
    private static final long MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private final TenantAssetRepository tenantAssetRepository;
    private final TenantDocumentEncryptionService encryptionService;

    public TenantAssetService(TenantAssetRepository tenantAssetRepository,
                              TenantDocumentEncryptionService encryptionService) {
        this.tenantAssetRepository = tenantAssetRepository;
        this.encryptionService = encryptionService;
    }

    public void storeIfProvided(Tenant tenant, TenantAssetType type, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return;
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Each uploaded file must be 5 MB or smaller");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file", ex);
        }

        String contentType = detectContentType(type, content);
        tenantAssetRepository.upsert(
                tenant.getId(),
                type.name(),
                contentType,
                encryptionService.encrypt(content));
    }

    public Optional<TenantAsset> find(Tenant tenant, TenantAssetType type) {
        return tenantAssetRepository.findByTenant_IdAndAssetType(tenant.getId(), type);
    }

    public Set<TenantAssetType> availableTypes(Tenant tenant) {
        return tenantAssetRepository.findAllByTenant_Id(tenant.getId()).stream()
                .map(TenantAsset::getAssetType)
                .collect(Collectors.toSet());
    }

    public byte[] decrypt(TenantAsset asset) {
        return encryptionService.decrypt(asset.getEncryptedContent());
    }

    private String detectContentType(TenantAssetType type, byte[] content) {
        if (isPng(content)) {
            return "image/png";
        }
        if (isJpeg(content)) {
            return "image/jpeg";
        }
        if (type == TenantAssetType.LOGO && isWebp(content)) {
            return "image/webp";
        }
        if (type != TenantAssetType.LOGO && isPdf(content)) {
            return "application/pdf";
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                type == TenantAssetType.LOGO
                        ? "Logo must be a PNG, JPEG, or WebP image"
                        : "Documents must be a PDF, PNG, or JPEG file");
    }

    private boolean isPng(byte[] content) {
        if (content.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (content[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean isJpeg(byte[] content) {
        return content.length >= 3
                && (content[0] & 0xFF) == 0xFF
                && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF;
    }

    private boolean isWebp(byte[] content) {
        return content.length >= 12
                && content[0] == 'R' && content[1] == 'I'
                && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E'
                && content[10] == 'B' && content[11] == 'P';
    }

    private boolean isPdf(byte[] content) {
        return content.length >= 5
                && content[0] == '%' && content[1] == 'P'
                && content[2] == 'D' && content[3] == 'F'
                && content[4] == '-';
    }
}
