package com.cardflow.authorization.authorization;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardflow.authorization.authorization.AuthorizationDtos.AuthorizationRequest;
import com.cardflow.authorization.card.CardAccountRepository;
import com.cardflow.authorization.card.CardStatus;
import com.cardflow.authorization.common.BadRequestException;
import com.cardflow.authorization.common.NotFoundException;
import com.cardflow.authorization.fraud.FraudAssessment;
import com.cardflow.authorization.fraud.ResilientFraudScorer;
import com.cardflow.authorization.fraud.ScoreRequest;

/**
 * Idempotency layer around {@link AuthorizationProcessor}.
 *
 * <ol>
 *   <li>Key seen before with the same request: return the original result (replay).</li>
 *   <li>Score fraud BEFORE the DB transaction, so the network call never holds the card lock.</li>
 *   <li>Key seen before with a different request: reject (422).</li>
 *   <li>New key: process it. If an identical request raced us and committed
 *       first, the unique constraint fails our insert; we then return theirs.</li>
 * </ol>
 * Not transactional itself, so a failed attempt's transaction is fully rolled
 * back before we look up the winner.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final AuthorizationRepository authorizations;
    private final AuthorizationProcessor processor;
    private final CardAccountRepository cards;
    private final ResilientFraudScorer fraudScorer;
    private final MeterRegistry metrics;
    private final Counter replays;

    public AuthorizationService(AuthorizationRepository authorizations, AuthorizationProcessor processor,
            CardAccountRepository cards, ResilientFraudScorer fraudScorer, MeterRegistry metrics) {
        this.authorizations = authorizations;
        this.processor = processor;
        this.cards = cards;
        this.fraudScorer = fraudScorer;
        this.metrics = metrics;
        this.replays = Counter.builder("cardflow.authorizations.replayed")
                .description("Requests answered from a stored result (idempotent retries)").register(metrics);
    }

    public record Result(Authorization authorization, boolean replayed) {
    }

    public Result authorize(String idempotencyKey, AuthorizationRequest request) {
        if (request.occurredAt() != null && request.occurredAt().isAfter(Instant.now().plus(MAX_CLOCK_SKEW))) {
            throw new BadRequestException("occurredAt must not be in the future");
        }
        String hash = RequestHasher.hash(request);

        Optional<Result> previous = replay(idempotencyKey, hash);
        if (previous.isPresent()) {
            replays.increment();
            return previous.get();
        }

        long t0 = System.nanoTime();
        FraudAssessment fraud = assessFraud(idempotencyKey, request);
        long t1 = System.nanoTime();
        try {
            Authorization auth = processor.process(idempotencyKey, hash, request, fraud);
            long t2 = System.nanoTime();
            log.info("Authorization {} {} {} amount={} fraud={}/{} timing: fraud={}ms decision={}ms", auth.getId(),
                    auth.getStatus(), auth.getDeclineReason() == null ? "" : auth.getDeclineReason(),
                    auth.getAmountMinor(), fraud.band(), fraud.scoredBy(), (t1 - t0) / 1_000_000,
                    (t2 - t1) / 1_000_000);
            Counter.builder("cardflow.authorizations")
                    .description("Authorization decisions")
                    .tag("status", auth.getStatus().name())
                    .tag("fraud_band", fraud.band().name())
                    .tag("scored_by", fraud.scoredBy().name())
                    .register(metrics).increment();
            return new Result(auth, false);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent request using the same key
            return replay(idempotencyKey, hash).orElseThrow(() -> e);
        }
    }

    /**
     * Unknown, inactive or wrong-currency cards will be declined anyway, so they
     * aren't scored (and don't pollute fraud-service's per-card history).
     * This read is unlocked; the decision re-checks everything under the row lock.
     */
    private FraudAssessment assessFraud(String idempotencyKey, AuthorizationRequest r) {
        var card = cards.findById(r.cardId());
        if (card.isEmpty() || card.get().getStatus() != CardStatus.ACTIVE
                || !card.get().getCurrency().equals(r.currency())) {
            return FraudAssessment.notScored();
        }
        var loc = r.merchantLocation() == null ? null
                : new ScoreRequest.Location(r.merchantLocation().lat(), r.merchantLocation().lon(),
                        r.merchantLocation().country());
        return fraudScorer.score(new ScoreRequest(idempotencyKey, r.cardId().toString(), r.amountMinor(), r.currency(),
                r.mcc(), r.merchantId(), r.channel() == null ? "CARD_PRESENT" : r.channel().name(), loc,
                r.occurredAt()));
    }

    @Transactional(readOnly = true)
    public Authorization get(UUID id) {
        return authorizations.findById(id)
                .orElseThrow(() -> new NotFoundException("Authorization " + id + " not found"));
    }

    private Optional<Result> replay(String idempotencyKey, String hash) {
        return authorizations.findByIdempotencyKey(idempotencyKey).map(existing -> {
            if (!existing.getRequestHash().equals(hash)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            return new Result(existing, true);
        });
    }
}
