ALTER TABLE products
    ADD COLUMN IF NOT EXISTS mrp NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS hsn_sac VARCHAR(100),
    ADD COLUMN IF NOT EXISTS set_size INTEGER,
    ADD COLUMN IF NOT EXISTS po_number VARCHAR(100),
    ADD COLUMN IF NOT EXISTS sales_count INTEGER;

UPDATE products
SET mrp = COALESCE(mrp, price),
    set_size = COALESCE(set_size, 1),
    sales_count = COALESCE(sales_count, 0),
    hsn_sac = COALESCE(NULLIF(TRIM(hsn_sac), ''), sku)
WHERE mrp IS NULL
   OR set_size IS NULL
   OR sales_count IS NULL
   OR hsn_sac IS NULL
   OR TRIM(hsn_sac) = '';
