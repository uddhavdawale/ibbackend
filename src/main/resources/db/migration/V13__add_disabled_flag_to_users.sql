ALTER TABLE users
    ADD COLUMN IF NOT EXISTS disabled BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE users
SET disabled = TRUE
WHERE lower(coalesce(role, '')) = 'disabled_user';

CREATE INDEX IF NOT EXISTS idx_users_tenant_disabled ON users (tenant_id, disabled);
