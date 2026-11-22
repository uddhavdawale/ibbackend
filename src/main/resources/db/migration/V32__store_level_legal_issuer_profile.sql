ALTER TABLE tenants
    ADD COLUMN state VARCHAR(100),
    ADD COLUMN gstin VARCHAR(15),
    ADD COLUMN phone VARCHAR(32),
    ADD COLUMN email VARCHAR(255);

UPDATE tenants t
SET gstin = COALESCE(
        NULLIF(BTRIM(t.gstin), ''),
        CASE WHEN CHAR_LENGTH(BTRIM(u.gstin)) <= 15 THEN NULLIF(BTRIM(u.gstin), '') END),
    phone = COALESCE(
        NULLIF(BTRIM(t.phone), ''),
        CASE WHEN CHAR_LENGTH(BTRIM(u.phone)) <= 32 THEN NULLIF(BTRIM(u.phone), '') END),
    email = COALESCE(NULLIF(BTRIM(t.email), ''), NULLIF(BTRIM(u.email), ''))
FROM (
    SELECT DISTINCT ON (tenant_id) tenant_id, gstin, phone, email
    FROM users
    WHERE tenant_id IS NOT NULL
      AND deleted_at IS NULL
      AND LOWER(TRIM(COALESCE(role, ''))) IN ('store_admin', 'retailer', 'owner', 'admin', 'shop_owner')
    ORDER BY tenant_id,
             CASE WHEN LOWER(TRIM(COALESCE(role, ''))) = 'store_admin' THEN 0 ELSE 1 END,
             CASE WHEN COALESCE(disabled, FALSE) THEN 1 ELSE 0 END,
             id
) u
WHERE u.tenant_id = t.id
  AND (t.gstin IS NULL OR BTRIM(t.gstin) = ''
       OR t.phone IS NULL OR BTRIM(t.phone) = ''
       OR t.email IS NULL OR BTRIM(t.email) = '');
