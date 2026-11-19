ALTER TABLE cash_tracking_configs
    ALTER COLUMN initialized_by_user_id DROP NOT NULL,
    ADD COLUMN initialization_source VARCHAR(32) NOT NULL DEFAULT 'ADMIN_RECONCILIATION';

ALTER TABLE cash_opening_balances
    ALTER COLUMN approved_by_user_id DROP NOT NULL,
    ADD COLUMN initialization_source VARCHAR(32) NOT NULL DEFAULT 'ADMIN_RECONCILIATION';
