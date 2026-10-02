-- Phase 5: keep the merchant and category of each card charge, so the ledger
-- (the system of record for posted money) can answer "spending by category".
-- NULL for transactions posted directly through the API (no merchant).
-- The append-only triggers from V1 still apply: these are set on INSERT only.
ALTER TABLE transactions
    ADD COLUMN merchant_id    VARCHAR(64),
    ADD COLUMN merchant_name  VARCHAR(200),
    ADD COLUMN mcc            VARCHAR(4) CHECK (mcc ~ '^[0-9]{4}$');

CREATE INDEX idx_ledger_entries_account_transaction ON ledger_entries (account_id, transaction_id);
