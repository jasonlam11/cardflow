package com.cardflow.authorization.authorization;

import static com.cardflow.authorization.AuthApiClient.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.cardflow.authorization.AuthApiClient;
import com.cardflow.authorization.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = "cardflow.outbox.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthorizationApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    AuthApiClient api;
    String card;

    @BeforeEach
    void setUp() throws Exception {
        api = new AuthApiClient(mvc);
        card = api.createCard(10_000);
    }

    @Test
    void approvesAndWritesOutboxEventInSameTransaction() throws Exception {
        String body = api.charge(newKey(), card, 4250)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.declineReason").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String authId = JsonPath.read(body, "$.id");

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE payload->'payload'->>'authorizationId' = ?",
                String.class, authId);
        assertThat((String) JsonPath.read(payload, "$.eventType")).isEqualTo("transaction.authorized");
        assertThat((Integer) JsonPath.read(payload, "$.schemaVersion")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(payload, "$.payload.amountMinor")).isEqualTo(4250);
        assertThat(payload).doesNotContain("tok_"); // no card token in events
    }

    @Test
    void declinesOverLimitWithoutEvent() throws Exception {
        String body = api.charge(newKey(), card, 10_001)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineReason").value("INSUFFICIENT_CREDIT"))
                .andReturn().getResponse().getContentAsString();
        String authId = JsonPath.read(body, "$.id");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE payload->'payload'->>'authorizationId' = ?",
                Long.class, authId)).isZero();
    }

    @Test
    void approvedChargesReduceAvailableCredit() throws Exception {
        api.charge(newKey(), card, 6000).andExpect(status().isCreated());
        api.charge(newKey(), card, 4001).andExpect(jsonPath("$.declineReason").value("INSUFFICIENT_CREDIT"));
        api.charge(newKey(), card, 4000).andExpect(status().isCreated());
    }

    @Test
    void unknownCardIsDeclinedNotErrored() throws Exception {
        api.charge(newKey(), UUID.randomUUID().toString(), 100)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declineReason").value("CARD_NOT_FOUND"));
    }

    @Test
    void retryWithSameKeyReturnsOriginalResponse() throws Exception {
        String key = newKey();
        String first = api.charge(key, card, 4250).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = api.charge(key, card, 4250)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authorizations WHERE idempotency_key = ?",
                Long.class, key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_events o JOIN authorizations a
                  ON o.payload->'payload'->>'authorizationId' = a.id::text
                 WHERE a.idempotency_key = ?""", Long.class, key)).isEqualTo(1);
    }

    @Test
    void declinedResultIsAlsoReplayed() throws Exception {
        String key = newKey();
        api.charge(key, card, 99_999).andExpect(status().isOk());
        api.charge(key, card, 99_999)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.declineReason").value("INSUFFICIENT_CREDIT"));
    }

    @Test
    void sameKeyDifferentBodyIsRejected() throws Exception {
        String key = newKey();
        api.charge(key, card, 4250).andExpect(status().isCreated());
        api.charge(key, card, 9999)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Idempotency key reused"));
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        api.charge(null, card, 100).andExpect(status().isBadRequest());
    }

    @Test
    void malformedIdempotencyKeyIsRejected() throws Exception {
        api.charge("has spaces and !!", card, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.idempotencyKey").exists());
    }

    @Test
    void invalidBodyIsRejected() throws Exception {
        api.charge(newKey(), card, -5).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amountMinor").exists());
    }
}
