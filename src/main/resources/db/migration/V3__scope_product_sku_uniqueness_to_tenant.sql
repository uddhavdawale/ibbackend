DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT tc.constraint_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.constraint_column_usage ccu
          ON tc.constraint_name = ccu.constraint_name
         AND tc.table_schema = ccu.table_schema
        WHERE tc.table_schema = 'public'
          AND tc.table_name = 'products'
          AND tc.constraint_type = 'UNIQUE'
          AND ccu.column_name = 'sku'
          AND tc.constraint_name <> 'uk_products_tenant_sku'
    LOOP
        EXECUTE format('ALTER TABLE public.products DROP CONSTRAINT IF EXISTS %I', constraint_name);
    END LOOP;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints tc
        WHERE tc.table_schema = 'public'
          AND tc.table_name = 'products'
          AND tc.constraint_name = 'uk_products_tenant_sku'
          AND tc.constraint_type = 'UNIQUE'
    ) THEN
        ALTER TABLE public.products
            ADD CONSTRAINT uk_products_tenant_sku UNIQUE (tenant_id, sku);
    END IF;
END $$;
