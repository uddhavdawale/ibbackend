ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS username VARCHAR(64);

-- Backfill legacy rows to keep existing retailers operational without forced reset.
UPDATE users
SET username = LOWER(
    COALESCE(
        NULLIF(REGEXP_REPLACE(SPLIT_PART(email, '@', 1), '[^a-zA-Z0-9._-]', '', 'g'), ''),
        'user'
    )
) || '_' || id
WHERE username IS NULL OR BTRIM(username) = '';

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username ON users (username);
CREATE INDEX IF NOT EXISTS idx_users_email ON users (email);
