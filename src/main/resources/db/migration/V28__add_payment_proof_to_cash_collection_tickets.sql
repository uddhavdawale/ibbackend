ALTER TABLE cash_handovers
    ADD COLUMN IF NOT EXISTS payment_method VARCHAR(20),
    ADD COLUMN IF NOT EXISTS payment_proof_sha256 VARCHAR(64);

ALTER TABLE cash_handovers
    ALTER COLUMN payment_method SET DEFAULT 'CASH';

UPDATE cash_handovers
SET payment_method = 'CASH'
WHERE payment_method IS NULL;

ALTER TABLE cash_handovers
    ALTER COLUMN payment_method SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_cash_handovers_payment_method'
          AND conrelid = 'cash_handovers'::regclass
    ) THEN
        ALTER TABLE cash_handovers
            ADD CONSTRAINT ck_cash_handovers_payment_method
            CHECK (payment_method IN ('CASH', 'UPI', 'CARD', 'UPI_CARD'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_cash_handovers_payment_proof'
          AND conrelid = 'cash_handovers'::regclass
    ) THEN
        ALTER TABLE cash_handovers
            ADD CONSTRAINT ck_cash_handovers_payment_proof
            CHECK (
                (payment_method = 'CASH' AND payment_proof_sha256 IS NULL)
                OR (payment_method <> 'CASH' AND payment_proof_sha256 IS NOT NULL)
            );
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS cash_collection_payment_proofs (
    ticket_id BIGINT PRIMARY KEY,
    content_type VARCHAR(100) NOT NULL,
    encrypted_content BYTEA NOT NULL,
    sha256 VARCHAR(64) NOT NULL
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_cash_collection_payment_proofs_ticket'
          AND conrelid = 'cash_collection_payment_proofs'::regclass
    ) THEN
        ALTER TABLE cash_collection_payment_proofs
            ADD CONSTRAINT fk_cash_collection_payment_proofs_ticket
            FOREIGN KEY (ticket_id) REFERENCES cash_handovers(id) ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_cash_collection_payment_proofs_content_type'
          AND conrelid = 'cash_collection_payment_proofs'::regclass
    ) THEN
        ALTER TABLE cash_collection_payment_proofs
            ADD CONSTRAINT ck_cash_collection_payment_proofs_content_type
            CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp'));
    END IF;
END $$;
