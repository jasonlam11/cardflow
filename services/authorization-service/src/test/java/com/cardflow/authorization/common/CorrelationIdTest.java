package com.cardflow.authorization.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.util.UUID;

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
class CorrelationIdTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void echoesValidIncomingId() throws Exception {
        mvc.perform(get("/cards/{id}", UUID.randomUUID()).header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
    }

    @Test
    void generatesIdWhenMissing() throws Exception {
        mvc.perform(get("/cards/{id}", UUID.randomUUID()))
                .andExpect(header().string("X-Correlation-Id", matchesPattern("^[0-9a-f-]{36}$")));
    }

    @Test
    void replacesUnsafeIdToPreventLogInjection() throws Exception {
        mvc.perform(get("/cards/{id}", UUID.randomUUID()).header("X-Correlation-Id", "x\nFAKE LOG LINE"))
                .andExpect(header().string("X-Correlation-Id", matchesPattern("^[0-9a-f-]{36}$")));
    }

    @Test
    void idIsStoredOnAuthorizationAndOutboxEvent() throws Exception {
        var api = new AuthApiClient(mvc);
        String card = api.createCard(10_000);
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/authorizations").contentType("application/json")
                .header("Idempotency-Key", AuthApiClient.newKey())
                .header("X-Correlation-Id", "trace-me-42")
                .content("""
                        {"cardId": "%s", "merchantId": "m1", "merchantName": "Shop", "mcc": "5411",
                         "amountMinor": 100, "currency": "USD"}""".formatted(card)))
                .andReturn().getResponse().getContentAsString();
        String authId = JsonPath.read(body, "$.id");

        assertThat(jdbc.queryForObject("SELECT correlation_id FROM authorizations WHERE id = ?::uuid",
                String.class, authId)).isEqualTo("trace-me-42");
        assertThat(jdbc.queryForObject("""
                SELECT correlation_id FROM outbox_events WHERE payload->'payload'->>'authorizationId' = ?""",
                String.class, authId)).isEqualTo("trace-me-42");
    }
}
