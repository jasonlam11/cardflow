-- Phase 2: ledger-service consumes transaction.authorized events.
-- V1 is never edited once shared; schema changes are new migrations.

-- Stable link to another service's entity, e.g. 'card:<uuid>' or 'merchant:<id>'.
-- Lets the consumer find or create the right accounts with no manual setup.
ALTER TABLE accounts ADD COLUMN external_ref VARCHAR(100) UNIQUE;

-- One row per event ever applied. Kafka delivers at least once, so the
-- consumer inserts here in the same DB transaction as the posting; a
-- duplicate delivery hits the primary key and is skipped.
CREATE TABLE processed_events (
    event_id        UUID          PRIMARY KEY,
    event_type      VARCHAR(100)  NOT NULL,
    transaction_id  UUID          REFERENCES transactions (id),
    processed_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
