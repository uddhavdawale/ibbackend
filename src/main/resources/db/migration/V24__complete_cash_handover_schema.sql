ALTER TABLE cash_handovers
    ADD COLUMN recipient_notes VARCHAR(1000);

ALTER TABLE cash_handovers
    ADD CONSTRAINT ck_cash_handovers_resolved_amount CHECK (
        resolved_amount IS NULL
        OR (resolved_amount <= declared_amount AND resolved_amount <= receiver_counted_amount)
    );
