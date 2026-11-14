ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS address VARCHAR(500),
    ADD COLUMN IF NOT EXISTS city VARCHAR(100),
    ADD COLUMN IF NOT EXISTS pincode VARCHAR(20);

UPDATE tenants t
SET address = COALESCE(t.address, u.address),
    city = COALESCE(t.city, u.city),
    pincode = COALESCE(t.pincode, u.pincode)
FROM (
    SELECT DISTINCT ON (tenant_id) tenant_id, address, city, pincode
    FROM users
    WHERE tenant_id IS NOT NULL
      AND deleted_at IS NULL
    ORDER BY tenant_id,
             CASE WHEN lower(role) = 'store_admin' THEN 0 ELSE 1 END,
             id
) u
WHERE u.tenant_id = t.id
  AND (t.address IS NULL OR t.city IS NULL OR t.pincode IS NULL);

CREATE TABLE IF NOT EXISTS tenant_assets (
    id BIGSERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    asset_type VARCHAR(32) NOT NULL CHECK (
        asset_type IN ('LOGO', 'GST_CERTIFICATE', 'PAN_CARD', 'AADHAAR_CARD')
    ),
    content_type VARCHAR(100) NOT NULL,
    encrypted_content BYTEA NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tenant_assets_tenant_type UNIQUE (tenant_id, asset_type)
);
