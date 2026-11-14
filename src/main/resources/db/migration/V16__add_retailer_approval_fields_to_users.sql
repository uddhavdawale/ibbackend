ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS approval_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS last_admin_action_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS admin_note TEXT NULL,
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE users
SET approval_status = 'APPROVED'
WHERE approval_status IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_approval_status
    ON users (approval_status);
