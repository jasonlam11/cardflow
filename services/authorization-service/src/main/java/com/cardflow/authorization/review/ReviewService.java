package com.cardflow.authorization.review;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardflow.authorization.authorization.Authorization;
import com.cardflow.authorization.authorization.AuthorizationRepository;
import com.cardflow.authorization.authorization.AuthorizationStatus;
import com.cardflow.authorization.authorization.TransactionAuthorized;
import com.cardflow.authorization.card.CardAccountRepository;
import com.cardflow.authorization.common.ConflictException;
import com.cardflow.authorization.common.CorrelationIdFilter;
import com.cardflow.authorization.common.NotFoundException;
import com.cardflow.authorization.outbox.EventEnvelope;
import com.cardflow.authorization.outbox.OutboxWriter;
import com.cardflow.authorization.review.ReviewDecision.Outcome;

/**
 * Resolves a PENDING_REVIEW authorization, in one DB transaction:
 * lock the row → check it's still pending → update status → record the
 * decision → (approve) write the ledger event to the outbox.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final AuthorizationRepository authorizations;
    private final ReviewDecisionRepository decisions;
    private final CardAccountRepository cards;
    private final OutboxWriter outbox;

    public ReviewService(AuthorizationRepository authorizations, ReviewDecisionRepository decisions,
            CardAccountRepository cards, OutboxWriter outbox) {
        this.authorizations = authorizations;
        this.decisions = decisions;
        this.cards = cards;
        this.outbox = outbox;
    }

    @Transactional
    public ReviewDecision decide(UUID authorizationId, Outcome outcome, String analyst, String note) {
        // Lock: if two analysts click at once, the second waits here, then sees it's no longer pending
        Authorization auth = authorizations.findByIdForUpdate(authorizationId)
                .orElseThrow(() -> new NotFoundException("Authorization " + authorizationId + " not found"));
        if (auth.getStatus() != AuthorizationStatus.PENDING_REVIEW) {
            throw new ConflictException("Authorization " + authorizationId + " was already resolved: "
                    + auth.getStatus());
        }

        AuthorizationStatus before = auth.getStatus();
        auth.resolveReview(outcome == Outcome.APPROVE);
        authorizations.saveAndFlush(auth);
        ReviewDecision decision = decisions.saveAndFlush(
                new ReviewDecision(auth.getId(), outcome, analyst, note, before, auth.getStatus()));

        if (outcome == Outcome.APPROVE) {
            var card = cards.findById(auth.getCardAccountId()).orElseThrow();
            var payload = new TransactionAuthorized(auth.getId(), card.getId(), card.getLast4(), auth.getMerchantId(),
                    auth.getMerchantName(), auth.getMcc(), auth.getAmountMinor(), auth.getCurrency());
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            outbox.append(EventEnvelope.of(TransactionAuthorized.EVENT_TYPE, TransactionAuthorized.SCHEMA_VERSION,
                    correlationId, payload), card.getId());
        }
        log.info("Review {} {} by {}: {} -> {}", auth.getId(), outcome, analyst, before, auth.getStatus());
        return decision;
    }
}
