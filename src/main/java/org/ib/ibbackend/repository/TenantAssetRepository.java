package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.TenantAsset;
import org.ib.ibbackend.entity.TenantAssetType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TenantAssetRepository extends JpaRepository<TenantAsset, Long> {
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO tenant_assets (tenant_id, asset_type, content_type, encrypted_content, updated_at)
            VALUES (:tenantId, :assetType, :contentType, :encryptedContent, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, asset_type)
            DO UPDATE SET content_type = EXCLUDED.content_type,
                          encrypted_content = EXCLUDED.encrypted_content,
                          updated_at = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    void upsert(@Param("tenantId") int tenantId,
                @Param("assetType") String assetType,
                @Param("contentType") String contentType,
                @Param("encryptedContent") byte[] encryptedContent);

    Optional<TenantAsset> findByTenant_IdAndAssetType(int tenantId, TenantAssetType assetType);

    List<TenantAsset> findAllByTenant_Id(int tenantId);
}
