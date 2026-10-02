package com.cardflow.authorization.review;

import static com.cardflow.authorization.AuthApiClient.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.cardflow.authorization.AuthApiClient;
import com.cardflow.authorization.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

/**
 * Human review: approve/reject, conflicts, audit trail, admin API key and the
 * DB guard on authorizations. Pending charges come from the rules fallback
 * (no fraud-service in tests): $2,500+ is routed to review.
 */
@SpringBootTest(properties = "cardflow.outbox.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReviewApiTest {

    static final String KEY = "test-admin-key";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    AuthApiClient api;
    String card;

    @BeforeEach
    void setUp() throws Exception {
        api = new AuthApiClient(mvc);
        card = api.createCard(10_000_000);
    }

    @Test
    void approvingPostsToLedgerViaOutboxAndRecordsDecision() throws Exception {
        String id = pendingCharge(300_000);

        review(id, "APPROVE", "Ana Analyst", "Customer confirmed by phone")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newStatus").value("APPROVED"))
                .andExpect(jsonPath("$.analyst").value("Ana Analyst"));

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE payload->'payload'->>'authorizationId' = ?",
                Long.class, id)).as("ledger event written on approval").isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT decision, previous_status, new_status FROM review_decisions WHERE authorization_id = ?::uuid", id))
                .containsEntry("decision", "APPROVE").containsEntry("previous_status", "PENDING_REVIEW")
                .containsEntry("new_status", "APPROVED");
    }

    @Test
    void rejectingDeclinesAndReleasesHeldCredit() throws Exception {
        String id = pendingCharge(300_000);
        mvc.perform(get("/cards/{id}", card)).andExpect(jsonPath("$.availableCreditMinor").value(9_700_000));

        review(id, "REJECT", "Ana Analyst", "Cardholder says not theirs").andExpect(status().isOk());

        assertThat(statusOf(id)).isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject("SELECT decline_reason FROM authorizations WHERE id = ?::uuid", String.class, id))
                .isEqualTo("ANALYST_REJECTED");
        mvc.perform(get("/cards/{id}", card)).andExpect(jsonPath("$.availableCreditMinor").value(10_000_000));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE payload->'payload'->>'authorizationId' = ?",
                Long.class, id)).isZero();
    }

    @Test
    void secondDecisionIsAConflict() throws Exception {
        String id = pendingCharge(300_000);
        review(id, "APPROVE", "Ana", null).andExpect(status().isOk());
        review(id, "REJECT", "Ben", "too late").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already resolved")));
    }

    @Test
    void twoAnalystsDecidingAtOnceProduceExactlyOneDecision() throws Exception {
        String id = pendingCharge(300_000);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (String[] who : new String[][] {{"APPROVE", "Ana"}, {"REJECT", "Ben"}}) {
                results.add(pool.submit(() -> {
                    start.await();
                    return review(id, who[0], who[1], "decided").andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_decisions WHERE authorization_id = ?::uuid",
                Long.class, id)).isEqualTo(1);
    }

    @Test
    void onlyPendingChargesCanBeReviewed() throws Exception {
        String approved = JsonPath.read(api.charge(newKey(), card, 1_000).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        review(approved, "REJECT", "Ana", "no").andExpect(status().isConflict());
    }

    @Test
    void rejectRequiresANoteAndAnalystIsValidated() throws Exception {
        String id = pendingCharge(300_000);
        review(id, "REJECT", "Ana", "  ").andExpect(status().isBadRequest());
        review(id, "APPROVE", "<script>", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.analyst").exists());
        assertThat(statusOf(id)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void adminEndpointsRequireTheApiKeyButMerchantEndpointsDoNot() throws Exception {
        mvc.perform(get("/reviews")).andExpect(status().isUnauthorized());
        mvc.perform(get("/stats").header("X-Admin-Api-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/authorizations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/authorizations/{id}/review", java.util.UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\",\"analyst\":\"x\"}")).andExpect(status().isUnauthorized());
        // Merchant-facing calls are unaffected
        api.charge(newKey(), card, 1_000).andExpect(status().isCreated());
        mvc.perform(get("/cards/{id}", card)).andExpect(status().isOk());
    }

    @Test
    void listingFiltersAndShowsOnlyLast4() throws Exception {
        String pending = pendingCharge(300_000);
        api.charge(newKey(), card, 1_000).andExpect(status().isCreated());

        mvc.perform(get("/authorizations").header("X-Admin-Api-Key", KEY).param("cardId", card))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].cardLast4").exists())
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("tok_"))));
        mvc.perform(get("/authorizations").header("X-Admin-Api-Key", KEY).param("cardId", card)
                        .param("status", "PENDING_REVIEW"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(pending))
                .andExpect(jsonPath("$.content[0].fraudReasons[0].code").value("LARGE_AMOUNT"));
    }

    @Test
    void queueIsOldestFirstAndDetailIncludesCardHistory() throws Exception {
        String older = pendingCharge(300_000);
        String newer = pendingCharge(310_000);

        String queue = mvc.perform(get("/reviews").header("X-Admin-Api-Key", KEY).param("size", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(queue, "$.content[*].id");
        assertThat(ids.indexOf(older)).isLessThan(ids.indexOf(newer));

        mvc.perform(get("/reviews/{id}", newer).header("X-Admin-Api-Key", KEY))
                .andExpect(jsonPath("$.authorization.id").value(newer))
                .andExpect(jsonPath("$.recentCardActivity[*].id", hasItem(older)));
    }

    @Test
    void decisionsAppearInTheAuditTrailAndStats() throws Exception {
        String id = pendingCharge(300_000);
        review(id, "APPROVE", "Audit Tester", null).andExpect(status().isOk());

        mvc.perform(get("/reviews/decisions").header("X-Admin-Api-Key", KEY).param("size", "100"))
                .andExpect(jsonPath("$.content[*].authorizationId", hasItem(id)));
        mvc.perform(get("/stats").header("X-Admin-Api-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last24hByStatus.APPROVED").isNumber())
                .andExpect(jsonPath("$.reviewDecisionsLast24h").isNumber());
    }

    @Test
    void databaseRefusesAnyOtherChangeToAnAuthorization() throws Exception {
        String approved = JsonPath.read(api.charge(newKey(), card, 1_000).andReturn().getResponse()
                .getContentAsString(), "$.id");
        String pending = pendingCharge(300_000);

        assertThatThrownBy(() -> jdbc.update("UPDATE authorizations SET status = 'DECLINED' WHERE id = ?::uuid", approved))
                .hasStackTraceContaining("is not allowed");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE authorizations SET status = 'APPROVED', amount_minor = 1 WHERE id = ?::uuid", pending))
                .hasStackTraceContaining("only status and decline_reason may change");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM authorizations WHERE id = ?::uuid", approved))
                .hasStackTraceContaining("append-only");
        review(pending, "APPROVE", "Ana", null).andExpect(status().isOk());
        assertThatThrownBy(() -> jdbc.update("DELETE FROM review_decisions WHERE authorization_id = ?::uuid", pending))
                .hasStackTraceContaining("append-only");
    }

    private String pendingCharge(long amount) throws Exception {
        String body = api.charge(newKey(), card, amount).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions review(String id, String decision, String analyst, String note) throws Exception {
        String json = note == null
                ? "{\"decision\":\"%s\",\"analyst\":\"%s\"}".formatted(decision, analyst)
                : "{\"decision\":\"%s\",\"analyst\":\"%s\",\"note\":\"%s\"}".formatted(decision, analyst, note);
        return mvc.perform(post("/authorizations/{id}/review", id).header("X-Admin-Api-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM authorizations WHERE id = ?::uuid", String.class, id);
    }
}
