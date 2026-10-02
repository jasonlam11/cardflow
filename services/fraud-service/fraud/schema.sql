-- Applied at startup; every statement is idempotent (IF NOT EXISTS).
-- One table doesn't need a migration framework yet; Alembic is the upgrade path.

CREATE TABLE IF NOT EXISTS card_activity (
    request_id     VARCHAR(64)   PRIMARY KEY,   -- one row per scored request, even if scoring is retried
    card_id        VARCHAR(64)   NOT NULL,
    occurred_at    TIMESTAMPTZ   NOT NULL,
    amount_minor   BIGINT        NOT NULL CHECK (amount_minor > 0),
    mcc            VARCHAR(4)    NOT NULL,
    merchant_id    VARCHAR(64)   NOT NULL,
    channel        VARCHAR(16)   NOT NULL,
    lat            DOUBLE PRECISION,
    lon            DOUBLE PRECISION,
    score          REAL          NOT NULL,
    band           VARCHAR(8)    NOT NULL,
    model_version  VARCHAR(40)   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Feature lookups: "this card's recent transactions, newest first"
CREATE INDEX IF NOT EXISTS idx_card_activity_card_time ON card_activity (card_id, occurred_at DESC);
