ALTER TABLE IF EXISTS invoices
    ADD COLUMN IF NOT EXISTS paid_amount NUMERIC(38,2) DEFAULT 0.00;

ALTER TABLE IF EXISTS invoices
    ADD COLUMN IF NOT EXISTS payment_status VARCHAR(255) DEFAULT 'PENDING';

UPDATE invoices
SET paid_amount = 0.00
WHERE paid_amount IS NULL;

UPDATE invoices
SET payment_status = 'PENDING'
WHERE payment_status IS NULL;

ALTER TABLE IF EXISTS invoices
    ALTER COLUMN paid_amount SET DEFAULT 0.00,
    ALTER COLUMN paid_amount SET NOT NULL;

ALTER TABLE IF EXISTS invoices
    ALTER COLUMN payment_status SET DEFAULT 'PENDING',
    ALTER COLUMN payment_status SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'invoices_payment_status_check'
    ) THEN
        ALTER TABLE invoices
            ADD CONSTRAINT invoices_payment_status_check
            CHECK (payment_status IN ('PENDING', 'PARTIAL', 'PAID', 'CANCELLED'));
    END IF;
END $$;
