-- CardFlow authorization schema. Synthetic data only: no card numbers are
-- ever stored, just a random token and the last 4 digits for display.

CREATE TABLE card_accounts (
    id                  UUID         PRIMARY KEY,
    card_token          VARCHAR(40)  NOT NULL UNIQUE,
    last4               VARCHAR(4)   NOT NULL CHECK (last4 ~ '^[0-9]{4}$'),
    status              VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    credit_limit_minor  BIGINT       NOT NULL CHECK (credit_limit_minor >= 0),
    currency            VARCHAR(3)   NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE authorizations (
    id               UUID         PRIMARY KEY,
    -- One row per client request. The UNIQUE constraint is what makes
    -- retries safe even when two copies of a request arrive at once.
    idempotency_key  VARCHAR(64)  NOT NULL UNIQUE,
    request_hash     VARCHAR(64)  NOT NULL,
    -- Null when the request named a card that doesn't exist
    card_account_id  UUID         REFERENCES card_accounts (id),
    merchant_id      VARCHAR(64)  NOT NULL,
    merchant_name    VARCHAR(200) NOT NULL,
    mcc              VARCHAR(4)   NOT NULL CHECK (mcc ~ '^[0-9]{4}$'),
    amount_minor     BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency         VARCHAR(3)   NOT NULL,
    status           VARCHAR(10)  NOT NULL CHECK (status IN ('APPROVED', 'DECLINED')),
    decline_reason   VARCHAR(40),
    correlation_id   VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Approved rows never have a reason; declined rows always do
    CHECK ((status = 'APPROVED') = (decline_reason IS NULL))
);

-- Available credit sums approved amounts per card; a partial index keeps that fast
CREATE INDEX idx_authorizations_card_approved
    ON authorizations (card_account_id) WHERE status = 'APPROVED';

-- Transactional outbox: written in the same DB transaction as the
-- authorization, published to Kafka afterwards by OutboxRelay.
CREATE TABLE outbox_events (
    id              UUID          PRIMARY KEY,   -- also the event ID consumers dedupe on
    event_type      VARCHAR(100)  NOT NULL,
    aggregate_id    UUID          NOT NULL,      -- Kafka message key (the card account)
    payload         JSONB         NOT NULL,
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,                 -- NULL = not yet sent
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(500)
);

-- The relay only ever scans unpublished rows, oldest first
CREATE INDEX idx_outbox_unpublished
    ON outbox_events (created_at) WHERE published_at IS NULL;
