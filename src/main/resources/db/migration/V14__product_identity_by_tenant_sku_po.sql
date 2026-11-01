-- Remove the legacy SKU-only uniqueness so the same item can be stocked under different POs.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE table_schema = 'public'
          AND table_name = 'products'
          AND constraint_name = 'uk_products_tenant_sku'
          AND constraint_type = 'UNIQUE'
    ) THEN
        ALTER TABLE public.products DROP CONSTRAINT uk_products_tenant_sku;
    END IF;
END $$;

ALTER TABLE IF EXISTS public.products
    ADD COLUMN IF NOT EXISTS po_number VARCHAR(100);

-- Keep the retailer flow stable: same tenant + same SKU + same PO stays a single product.
-- Different POs remain distinct stock rows while preserving the previous behaviour for the same PO.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE table_schema = 'public'
          AND table_name = 'products'
          AND constraint_name = 'uk_products_tenant_sku_po_number'
          AND constraint_type = 'UNIQUE'
    ) THEN
        ALTER TABLE public.products
            ADD CONSTRAINT uk_products_tenant_sku_po_number UNIQUE (tenant_id, sku, po_number);
    END IF;
END $$;
