ALTER TABLE cash_reconciliations
    ADD COLUMN incoming_handovers NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (incoming_handovers >= 0),
    ADD COLUMN outgoing_handovers NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (outgoing_handovers >= 0);
