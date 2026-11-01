ALTER TABLE users
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS deleted_by BIGINT NULL;

CREATE INDEX IF NOT EXISTS idx_users_tenant_deleted_at ON users (tenant_id, deleted_at);
CREATE INDEX IF NOT EXISTS idx_users_email_deleted_at ON users (email, deleted_at);
CREATE INDEX IF NOT EXISTS idx_users_username_deleted_at ON users (username, deleted_at);
