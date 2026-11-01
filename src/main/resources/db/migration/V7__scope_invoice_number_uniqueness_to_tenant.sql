DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'invoices'::regclass
          AND contype = 'u'
          AND conname <> 'uk_invoices_tenant_invoice_no'
    LOOP
        EXECUTE format('ALTER TABLE invoices DROP CONSTRAINT IF EXISTS %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE invoices
    ADD CONSTRAINT uk_invoices_tenant_invoice_no UNIQUE (tenant_id, invoice_no);

DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'non_gst_invoices'::regclass
          AND contype = 'u'
          AND conname <> 'uk_non_gst_invoices_tenant_invoice_no'
    LOOP
        EXECUTE format('ALTER TABLE non_gst_invoices DROP CONSTRAINT IF EXISTS %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE non_gst_invoices
    ADD CONSTRAINT uk_non_gst_invoices_tenant_invoice_no UNIQUE (tenant_id, invoice_no);
