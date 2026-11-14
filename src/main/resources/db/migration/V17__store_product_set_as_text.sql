ALTER TABLE products
    ALTER COLUMN set_size TYPE VARCHAR(100);

UPDATE products SET set_size = '1' WHERE set_size IS NULL OR TRIM(set_size) = '';