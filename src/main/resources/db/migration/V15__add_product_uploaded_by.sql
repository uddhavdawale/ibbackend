ALTER TABLE IF EXISTS public.products
    ADD COLUMN IF NOT EXISTS uploaded_by BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_products_uploaded_by'
    ) THEN
        ALTER TABLE public.products
            ADD CONSTRAINT fk_products_uploaded_by
            FOREIGN KEY (uploaded_by) REFERENCES public.users(id);
    END IF;
END $$;
