package com.cardflow.authorization.fraud;

import static com.cardflow.authorization.AuthApiClient.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.cardflow.authorization.AuthApiClient;
import com.cardflow.authorization.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/** authorization-service talking to a fake fraud-service: bands, fallback, timeout and circuit breaker. */
@SpringBootTest(properties = "cardflow.outbox.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FraudIntegrationTest {

    static final FakeFraudService fake;

    static {
        try {
            fake = new FakeFraudService();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void fraudUrl(DynamicPropertyRegistry registry) {
        registry.add("cardflow.fraud.url", fake::url);
    }

    @AfterAll
    static void stop() {
        fake.server.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ResilientFraudScorer scorer;

    AuthApiClient api;
    String card;

    @BeforeEach
    void setUp() throws Exception {
        fake.reset();
        scorer.reset();
        api = new AuthApiClient(mvc);
        card = api.createCard(1_000_000);
    }

    @Test
    void lowBandIsApprovedWithModelScoreRecorded() throws Exception {
        fake.score = 0.02;
        api.charge(newKey(), card, 2_500)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraud.scoredBy").value("MODEL"))
                .andExpect(jsonPath("$.fraud.band").value("LOW"))
                .andExpect(jsonPath("$.fraud.score").value(0.02))
                .andExpect(jsonPath("$.fraud.modelVersion").value("fraud-xgb-test"));
    }

    @Test
    void highBandIsDeclinedAsFraudWithReasonsAndNoEvent() throws Exception {
        fake.band = "HIGH";
        fake.score = 0.99;
        String id = id(api.charge(newKey(), card, 2_500)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineReason").value("FRAUD_SUSPECTED"))
                .andExpect(jsonPath("$.fraud.reasons[0].code").value("HIGH_VELOCITY")));
        assertThat(outboxRowsFor(id)).isZero();
        // Reasons are stored for the audit trail
        assertThat(jdbc.queryForObject("SELECT fraud_reasons->0->>'code' FROM authorizations WHERE id = ?::uuid",
                String.class, id)).isEqualTo("HIGH_VELOCITY");
    }

    @Test
    void reviewBandIsHeldForHumanReviewAndReservesCredit() throws Exception {
        fake.band = "REVIEW";
        fake.score = 0.9;
        String id = id(api.charge(newKey(), card, 400_000)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.declineReason").doesNotExist()));
        assertThat(outboxRowsFor(id)).as("ledger only hears about it after a human approves").isZero();
        mvc.perform(get("/cards/{id}", card)).andExpect(jsonPath("$.availableCreditMinor").value(600_000));
    }

    @Test
    void fraudServiceErrorFallsBackToRules() throws Exception {
        fake.mode = FakeFraudService.Mode.ERROR;
        api.charge(newKey(), card, 2_500)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraud.scoredBy").value("RULES_FALLBACK"))
                .andExpect(jsonPath("$.fraud.score").doesNotExist());
        // Rules never decline; a large charge goes to review instead
        api.charge(newKey(), card, 250_000)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fraud.reasons[0].code").value("LARGE_AMOUNT"));
    }

    @Test
    void slowFraudServiceTimesOutQuicklyAndFallsBack() throws Exception {
        fake.mode = FakeFraudService.Mode.SLOW;
        fake.slowMillis = 2_000;
        long start = System.nanoTime();
        api.charge(newKey(), card, 2_500)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraud.scoredBy").value("RULES_FALLBACK"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).as("bounded by the 300 ms read timeout, not the 2 s response").isLessThan(1_000);
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndStopsCallingFraudService() throws Exception {
        fake.mode = FakeFraudService.Mode.ERROR;
        for (int i = 0; i < 15; i++) {
            api.charge(newKey(), card, 1_000).andExpect(status().isCreated());
        }
        assertThat(scorer.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        // minimum-calls is 10: after 10 failures the circuit opened, and the next 5 never reached fraud-service
        assertThat(fake.calls.get()).isEqualTo(10);
    }

    @Test
    void forwardsIdempotencyKeyAndCorrelationIdButNoCardToken() throws Exception {
        String key = newKey();
        mvc.perform(post("/authorizations").contentType("application/json")
                .header("Idempotency-Key", key).header("X-Correlation-Id", "trace-fraud-1")
                .content("""
                        {"cardId": "%s", "merchantId": "m1", "merchantName": "Shop", "mcc": "5411",
                         "amountMinor": 100, "currency": "USD", "channel": "CARD_PRESENT",
                         "merchantLocation": {"lat": 40.7, "lon": -74.0, "country": "US"},
                         "occurredAt": "2026-03-01T12:00:00Z"}""".formatted(card)))
                .andExpect(status().isCreated());
        assertThat(fake.correlationIds).containsExactly("trace-fraud-1");
        String body = fake.bodies.getFirst();
        assertThat((String) JsonPath.read(body, "$.requestId")).isEqualTo(key);
        assertThat((Double) JsonPath.read(body, "$.merchantLocation.lat")).isEqualTo(40.7);
        assertThat((String) JsonPath.read(body, "$.occurredAt")).isEqualTo("2026-03-01T12:00:00Z");
        assertThat(body).doesNotContain("tok_");
    }

    @Test
    void unknownCardIsNotScored() throws Exception {
        api.charge(newKey(), UUID.randomUUID().toString(), 100)
                .andExpect(jsonPath("$.declineReason").value("CARD_NOT_FOUND"))
                .andExpect(jsonPath("$.fraud.scoredBy").value("NOT_SCORED"));
        assertThat(fake.calls.get()).isZero();
    }

    @Test
    void futureOccurredAtIsRejected() throws Exception {
        mvc.perform(post("/authorizations").contentType("application/json").header("Idempotency-Key", newKey())
                .content("""
                        {"cardId": "%s", "merchantId": "m1", "merchantName": "Shop", "mcc": "5411",
                         "amountMinor": 100, "currency": "USD", "occurredAt": "2099-01-01T00:00:00Z"}""".formatted(card)))
                .andExpect(status().isBadRequest());
        assertThat(fake.calls.get()).isZero();
    }

    private String outboxRowsForSql() {
        return "SELECT COUNT(*) FROM outbox_events WHERE payload->'payload'->>'authorizationId' = ?";
    }

    private long outboxRowsFor(String authId) {
        return jdbc.queryForObject(outboxRowsForSql(), Long.class, authId);
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
