ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS family_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS replacement_token_ciphertext TEXT,
    ADD COLUMN IF NOT EXISTS replacement_grace_until TIMESTAMP NULL;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_replaced_by_token_hash
    ON refresh_tokens(replaced_by_token_hash);

WITH RECURSIVE token_families(token_hash, family_id) AS (
    SELECT token.token_hash, token.token_hash
    FROM refresh_tokens token
    WHERE NOT EXISTS (
        SELECT 1
        FROM refresh_tokens predecessor
        WHERE predecessor.replaced_by_token_hash = token.token_hash
    )
    UNION ALL
    SELECT child.token_hash, family.family_id
    FROM token_families family
    JOIN refresh_tokens parent ON parent.token_hash = family.token_hash
    JOIN refresh_tokens child ON child.token_hash = parent.replaced_by_token_hash
)
UPDATE refresh_tokens token
SET family_id = COALESCE(families.family_id, token.token_hash)
FROM (
    SELECT token_hash, family_id FROM token_families
) families
WHERE token.token_hash = families.token_hash;

UPDATE refresh_tokens
SET family_id = token_hash
WHERE family_id IS NULL;

ALTER TABLE refresh_tokens
    ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family_id
    ON refresh_tokens(family_id);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at
    ON refresh_tokens(expires_at);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_replacement_grace_until
    ON refresh_tokens(replacement_grace_until);
