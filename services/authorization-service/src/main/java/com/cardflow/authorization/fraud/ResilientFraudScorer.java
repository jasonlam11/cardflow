package com.cardflow.authorization.fraud;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;

/**
 * Calls fraud-service through a circuit breaker; falls back to local rules.
 *
 * <pre>
 *  CLOSED ──(≥ failureRate% of the last N calls failed)──▶ OPEN
 *    ▲                                                        │ wait openSeconds
 *    └──(test calls succeed)── HALF_OPEN ◀────────────────────┘
 * </pre>
 * While OPEN, calls fail instantly (no network, no timeout wait), so a dead
 * fraud-service adds ~0 ms to authorizations instead of a timeout each time.
 */
@Component
public class ResilientFraudScorer {

    private static final Logger log = LoggerFactory.getLogger(ResilientFraudScorer.class);

    private final FraudServiceClient client;
    private final RuleBasedFraudScorer rules;
    private final CircuitBreaker breaker;
    private final MeterRegistry metrics;

    public ResilientFraudScorer(FraudServiceClient client, RuleBasedFraudScorer rules, FraudProperties props,
            MeterRegistry metrics) {
        this.metrics = metrics;
        this.client = client;
        this.rules = rules;
        this.breaker = CircuitBreaker.of("fraud-service", CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(props.circuitWindowSize())
                .minimumNumberOfCalls(props.circuitMinimumCalls())
                .failureRateThreshold(props.circuitFailureRatePercent())
                .waitDurationInOpenState(Duration.ofSeconds(props.circuitOpenSeconds()))
                .permittedNumberOfCallsInHalfOpenState(props.circuitHalfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build());
        breaker.getEventPublisher().onStateTransition(e ->
                log.warn("fraud-service circuit breaker: {}", e.getStateTransition()));
        Gauge.builder("cardflow.fraud.circuit.open", breaker,
                        b -> b.getState() == CircuitBreaker.State.CLOSED ? 0 : 1)
                .description("1 while the fraud-service circuit breaker is open or half-open").register(metrics);
    }

    public FraudAssessment score(ScoreRequest request) {
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "model";
        try {
            return breaker.executeSupplier(() -> client.score(request));
        } catch (CallNotPermittedException e) {
            // Circuit open: don't even try
            outcome = "circuit_open";
            return rules.score(request.amountMinor(), request.mcc());
        } catch (Exception e) {
            outcome = "fallback";
            log.warn("fraud-service call failed ({}); using rule-based fallback", e.getClass().getSimpleName());
            return rules.score(request.amountMinor(), request.mcc());
        } finally {
            sample.stop(Timer.builder("cardflow.fraud.score")
                    .description("Time to get a fraud assessment, by how it was produced")
                    .tag("outcome", outcome).publishPercentileHistogram().register(metrics));
        }
    }

    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }

    /** For tests: start from a clean, closed circuit. */
    void reset() {
        breaker.reset();
    }
}
