-- Phase 4: human review of PENDING_REVIEW authorizations, with an audit trail.

-- One decision per authorization, never edited or deleted
CREATE TABLE review_decisions (
    id                UUID          PRIMARY KEY,
    authorization_id  UUID          NOT NULL UNIQUE REFERENCES authorizations (id),
    decision          VARCHAR(8)    NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    analyst           VARCHAR(100)  NOT NULL,
    note              VARCHAR(1000),
    previous_status   VARCHAR(16)   NOT NULL,
    new_status        VARCHAR(16)   NOT NULL,
    decided_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_review_decisions_decided_at ON review_decisions (decided_at DESC);

CREATE FUNCTION reject_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER review_decisions_immutable
    BEFORE UPDATE OR DELETE ON review_decisions
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Authorizations: the ONLY allowed change is a review outcome on a pending row.
-- Everything else about a recorded decision is immutable, and rows are never deleted.
CREATE FUNCTION guard_authorization_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PENDING_REVIEW' OR NEW.status NOT IN ('APPROVED', 'DECLINED') THEN
        RAISE EXCEPTION 'authorization %: status % -> % is not allowed', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.id, NEW.idempotency_key, NEW.request_hash, NEW.card_account_id, NEW.merchant_id, NEW.amount_minor,
        NEW.currency, NEW.fraud_score, NEW.fraud_band, NEW.scored_by, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.idempotency_key, OLD.request_hash, OLD.card_account_id, OLD.merchant_id, OLD.amount_minor,
        OLD.currency, OLD.fraud_score, OLD.fraud_band, OLD.scored_by, OLD.created_at) THEN
        RAISE EXCEPTION 'authorization %: only status and decline_reason may change', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER authorizations_guard_update
    BEFORE UPDATE ON authorizations
    FOR EACH ROW EXECUTE FUNCTION guard_authorization_update();

CREATE TRIGGER authorizations_no_delete
    BEFORE DELETE ON authorizations
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Dashboard queries: newest first overall, by status, and the review queue oldest first
CREATE INDEX idx_authorizations_created_at ON authorizations (created_at DESC);
CREATE INDEX idx_authorizations_status_created ON authorizations (status, created_at);
