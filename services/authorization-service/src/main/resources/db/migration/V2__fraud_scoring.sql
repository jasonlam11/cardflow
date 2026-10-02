-- Phase 3: fraud scoring and the human review band.

-- Optional request context, used by fraud scoring and kept for analysts
ALTER TABLE authorizations
    ADD COLUMN channel           VARCHAR(16) CHECK (channel IN ('CARD_PRESENT', 'ECOMMERCE')),
    ADD COLUMN merchant_lat      DOUBLE PRECISION,
    ADD COLUMN merchant_lon      DOUBLE PRECISION,
    ADD COLUMN merchant_country  VARCHAR(2),
    ADD COLUMN occurred_at       TIMESTAMPTZ;

-- Fraud assessment recorded with every decision (audit trail for analysts)
ALTER TABLE authorizations
    ADD COLUMN fraud_score    DOUBLE PRECISION,
    ADD COLUMN fraud_band     VARCHAR(8)  CHECK (fraud_band IN ('LOW', 'REVIEW', 'HIGH')),
    ADD COLUMN fraud_reasons  JSONB,
    ADD COLUMN scored_by      VARCHAR(16) CHECK (scored_by IN ('MODEL', 'RULES_FALLBACK', 'NOT_SCORED')),
    ADD COLUMN model_version  VARCHAR(40);

-- New status for borderline fraud scores: a human decides (Phase 4).
-- 'PENDING_REVIEW' doesn't fit V1's VARCHAR(10), so widen the column first.
ALTER TABLE authorizations ALTER COLUMN status TYPE VARCHAR(16);
ALTER TABLE authorizations DROP CONSTRAINT authorizations_status_check;
ALTER TABLE authorizations ADD CONSTRAINT authorizations_status_check
    CHECK (status IN ('APPROVED', 'DECLINED', 'PENDING_REVIEW'));

-- Only declines carry a reason (was: approved <=> no reason)
ALTER TABLE authorizations DROP CONSTRAINT authorizations_check;
ALTER TABLE authorizations ADD CONSTRAINT authorizations_decline_reason_check
    CHECK ((status = 'DECLINED') = (decline_reason IS NOT NULL));

-- A charge waiting for review still reserves credit, like a hold
DROP INDEX idx_authorizations_card_approved;
CREATE INDEX idx_authorizations_card_holding
    ON authorizations (card_account_id) WHERE status IN ('APPROVED', 'PENDING_REVIEW');
