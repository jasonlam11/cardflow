package com.cardflow.authorization.authorization;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cardflow.authorization.authorization.AuthorizationDtos.AuthorizationRequest;
import com.cardflow.authorization.card.CardAccount;
import com.cardflow.authorization.card.CardAccountRepository;
import com.cardflow.authorization.common.CorrelationIdFilter;
import com.cardflow.authorization.fraud.FraudAssessment;
import com.cardflow.authorization.outbox.EventEnvelope;
import com.cardflow.authorization.outbox.OutboxWriter;

/**
 * Makes and records one authorization decision in a single DB transaction:
 * lock card → decide → insert authorization → insert outbox event (approvals only).
 * Either all of it commits, or none of it does. The fraud assessment is
 * computed before this transaction, so no network call happens while the
 * card row is locked.
 */
@Component
class AuthorizationProcessor {

    private final CardAccountRepository cards;
    private final AuthorizationRepository authorizations;
    private final OutboxWriter outbox;

    AuthorizationProcessor(CardAccountRepository cards, AuthorizationRepository authorizations, OutboxWriter outbox) {
        this.cards = cards;
        this.authorizations = authorizations;
        this.outbox = outbox;
    }

    @Transactional
    Authorization process(String idempotencyKey, String requestHash, AuthorizationRequest req, FraudAssessment fraud) {
        // Row lock: concurrent charges on this card queue up here, so each one
        // sees the credit already used (or held) by the ones before it
        CardAccount card = cards.findByIdForUpdate(req.cardId()).orElse(null);
        long available = card == null ? 0 : card.getCreditLimitMinor() - cards.sumApprovedMinor(card.getId());

        Decision decision = AuthorizationRules.decide(card, available, req.amountMinor(), req.currency(), fraud);

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        Authorization auth = new Authorization(idempotencyKey, requestHash, card == null ? null : card.getId(), req,
                decision, fraud, correlationId);
        // Flush now so a duplicate idempotency key fails here, before the outbox insert
        authorizations.saveAndFlush(auth);

        if (decision.approved()) {
            var payload = new TransactionAuthorized(auth.getId(), card.getId(), card.getLast4(), req.merchantId(),
                    req.merchantName(), req.mcc(), req.amountMinor(), req.currency());
            outbox.append(EventEnvelope.of(TransactionAuthorized.EVENT_TYPE, TransactionAuthorized.SCHEMA_VERSION,
                    correlationId, payload), card.getId());
        }
        return auth;
    }
}
