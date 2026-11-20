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
          AND cardinality(conkey) = 1
          AND conkey[1] = (
              SELECT attnum
              FROM pg_attribute
              WHERE attrelid = 'invoices'::regclass
                AND attname = 'invoice_no'
                AND NOT attisdropped
          )
    LOOP
        EXECUTE format('ALTER TABLE invoices DROP CONSTRAINT IF EXISTS %I', c.conname);
    END LOOP;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.invoices'::regclass
          AND conname = 'uk_invoices_tenant_invoice_no'
          AND contype = 'u'
          AND conkey = ARRAY[
              (SELECT attnum FROM pg_attribute WHERE attrelid = 'public.invoices'::regclass AND attname = 'tenant_id' AND NOT attisdropped),
              (SELECT attnum FROM pg_attribute WHERE attrelid = 'public.invoices'::regclass AND attname = 'invoice_no' AND NOT attisdropped)
          ]::smallint[]
    ) THEN
        NULL;
    ELSIF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.invoices'::regclass
          AND conname = 'uk_invoices_tenant_invoice_no'
    ) THEN
        RAISE EXCEPTION 'Constraint uk_invoices_tenant_invoice_no exists with an unexpected definition';
    ELSIF to_regclass('public.uk_invoices_tenant_invoice_no') IS NOT NULL THEN
        IF EXISTS (
            SELECT 1
            FROM pg_class tbl
            JOIN pg_index idx ON idx.indrelid = tbl.oid
            JOIN pg_class index_relation ON index_relation.oid = idx.indexrelid
            WHERE tbl.oid = 'public.invoices'::regclass
              AND index_relation.oid = to_regclass('public.uk_invoices_tenant_invoice_no')
              AND idx.indisunique
              AND idx.indisvalid
              AND idx.indisready
              AND idx.indpred IS NULL
              AND idx.indexprs IS NULL
              AND idx.indnatts = 2
              AND idx.indkey[0] = (
                  SELECT attnum FROM pg_attribute
                  WHERE attrelid = tbl.oid AND attname = 'tenant_id' AND NOT attisdropped
              )
              AND idx.indkey[1] = (
                  SELECT attnum FROM pg_attribute
                  WHERE attrelid = tbl.oid AND attname = 'invoice_no' AND NOT attisdropped
              )
        ) THEN
            ALTER TABLE public.invoices
                ADD CONSTRAINT uk_invoices_tenant_invoice_no
                UNIQUE USING INDEX uk_invoices_tenant_invoice_no;
        ELSE
            RAISE EXCEPTION 'Index uk_invoices_tenant_invoice_no exists but does not enforce the expected invoice uniqueness';
        END IF;
    ELSE
        ALTER TABLE public.invoices
            ADD CONSTRAINT uk_invoices_tenant_invoice_no
            UNIQUE (tenant_id, invoice_no);
    END IF;
END $$;

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
          AND cardinality(conkey) = 1
          AND conkey[1] = (
              SELECT attnum
              FROM pg_attribute
              WHERE attrelid = 'non_gst_invoices'::regclass
                AND attname = 'invoice_no'
                AND NOT attisdropped
          )
    LOOP
        EXECUTE format('ALTER TABLE non_gst_invoices DROP CONSTRAINT IF EXISTS %I', c.conname);
    END LOOP;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.non_gst_invoices'::regclass
          AND conname = 'uk_non_gst_invoices_tenant_invoice_no'
          AND contype = 'u'
          AND conkey = ARRAY[
              (SELECT attnum FROM pg_attribute WHERE attrelid = 'public.non_gst_invoices'::regclass AND attname = 'tenant_id' AND NOT attisdropped),
              (SELECT attnum FROM pg_attribute WHERE attrelid = 'public.non_gst_invoices'::regclass AND attname = 'invoice_no' AND NOT attisdropped)
          ]::smallint[]
    ) THEN
        NULL;
    ELSIF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.non_gst_invoices'::regclass
          AND conname = 'uk_non_gst_invoices_tenant_invoice_no'
    ) THEN
        RAISE EXCEPTION 'Constraint uk_non_gst_invoices_tenant_invoice_no exists with an unexpected definition';
    ELSIF to_regclass('public.uk_non_gst_invoices_tenant_invoice_no') IS NOT NULL THEN
        IF EXISTS (
            SELECT 1
            FROM pg_class tbl
            JOIN pg_index idx ON idx.indrelid = tbl.oid
            JOIN pg_class index_relation ON index_relation.oid = idx.indexrelid
            WHERE tbl.oid = 'public.non_gst_invoices'::regclass
              AND index_relation.oid = to_regclass('public.uk_non_gst_invoices_tenant_invoice_no')
              AND idx.indisunique
              AND idx.indisvalid
              AND idx.indisready
              AND idx.indpred IS NULL
              AND idx.indexprs IS NULL
              AND idx.indnatts = 2
              AND idx.indkey[0] = (
                  SELECT attnum FROM pg_attribute
                  WHERE attrelid = tbl.oid AND attname = 'tenant_id' AND NOT attisdropped
              )
              AND idx.indkey[1] = (
                  SELECT attnum FROM pg_attribute
                  WHERE attrelid = tbl.oid AND attname = 'invoice_no' AND NOT attisdropped
              )
        ) THEN
            ALTER TABLE public.non_gst_invoices
                ADD CONSTRAINT uk_non_gst_invoices_tenant_invoice_no
                UNIQUE USING INDEX uk_non_gst_invoices_tenant_invoice_no;
        ELSE
            RAISE EXCEPTION 'Index uk_non_gst_invoices_tenant_invoice_no exists but does not enforce the expected invoice uniqueness';
        END IF;
    ELSE
        ALTER TABLE public.non_gst_invoices
            ADD CONSTRAINT uk_non_gst_invoices_tenant_invoice_no
            UNIQUE (tenant_id, invoice_no);
    END IF;
END $$;
