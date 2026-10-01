-- CardFlow ledger schema.
-- Money is stored as BIGINT minor units (cents). Never floats.
-- Balances are never stored; they are derived from ledger_entries.

CREATE TABLE accounts (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    type        VARCHAR(20)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE')),
    currency    VARCHAR(3)   NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Lets ledger_entries reference (id, currency) so an entry's currency
    -- must match its account's currency
    UNIQUE (id, currency)
);

CREATE TABLE transactions (
    id           UUID         PRIMARY KEY,
    description  VARCHAR(500) NOT NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id              UUID        PRIMARY KEY,
    transaction_id  UUID        NOT NULL REFERENCES transactions (id),
    account_id      UUID        NOT NULL,
    direction       VARCHAR(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency        VARCHAR(3)  NOT NULL,
    FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

CREATE INDEX idx_ledger_entries_account     ON ledger_entries (account_id);
CREATE INDEX idx_ledger_entries_transaction ON ledger_entries (transaction_id);
CREATE INDEX idx_transactions_occurred_at   ON transactions (occurred_at DESC);

-- ---------------------------------------------------------------------------
-- Rule 1: every transaction balances (sum of debits = sum of credits), has at
-- least two entries, and uses a single currency.
-- A CHECK constraint only sees one row, so this is a constraint trigger
-- DEFERRED to COMMIT, when all of the transaction's entries exist.
-- ---------------------------------------------------------------------------
CREATE FUNCTION assert_transaction_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    txn_id      UUID;
    net         BIGINT;
    entry_count INT;
    currencies  INT;
BEGIN
    -- Shared by both triggers; NEW has different columns on each table
    IF TG_TABLE_NAME = 'transactions' THEN
        txn_id := NEW.id;
    ELSE
        txn_id := NEW.transaction_id;
    END IF;

    SELECT COALESCE(SUM(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0),
           COUNT(*),
           COUNT(DISTINCT currency)
      INTO net, entry_count, currencies
      FROM ledger_entries
     WHERE transaction_id = txn_id;

    IF entry_count < 2 THEN
        RAISE EXCEPTION 'transaction % has % entries; at least 2 required', txn_id, entry_count
            USING ERRCODE = 'check_violation';
    END IF;
    IF net <> 0 THEN
        RAISE EXCEPTION 'transaction % is unbalanced: debits - credits = %', txn_id, net
            USING ERRCODE = 'check_violation';
    END IF;
    IF currencies <> 1 THEN
        RAISE EXCEPTION 'transaction % mixes % currencies', txn_id, currencies
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();

-- Also fires for a transaction inserted with no entries at all
CREATE CONSTRAINT TRIGGER transactions_have_entries
    AFTER INSERT ON transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();

-- ---------------------------------------------------------------------------
-- Rule 2: posted history is immutable. Corrections are new, offsetting
-- transactions, never edits.
-- ---------------------------------------------------------------------------
CREATE FUNCTION reject_ledger_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER transactions_immutable
    BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER transactions_no_truncate
    BEFORE TRUNCATE ON transactions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
