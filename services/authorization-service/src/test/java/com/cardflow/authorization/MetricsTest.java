package com.cardflow.authorization;

import static com.cardflow.authorization.AuthApiClient.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import io.micrometer.core.instrument.MeterRegistry;

/** Business metrics are recorded (read from the registry; Boot disables export in tests). */
@SpringBootTest(properties = "cardflow.outbox.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MetricsTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry registry;

    @Test
    void authorizationsReplaysFraudScoringAndOutboxAreMeasured() throws Exception {
        var api = new AuthApiClient(mvc);
        String card = api.createCard(1_000_000);
        double approved = count("cardflow.authorizations", "status", "APPROVED");
        double replays = registry.counter("cardflow.authorizations.replayed").count();
        long fallbackScores = timerCount("fallback") + timerCount("circuit_open");

        String key = newKey();
        api.charge(key, card, 1_000).andExpect(status().isCreated());
        api.charge(newKey(), card, 2_000).andExpect(status().isCreated());
        api.charge(key, card, 1_000).andExpect(status().isCreated()); // retry

        assertThat(count("cardflow.authorizations", "status", "APPROVED")).isEqualTo(approved + 2);
        assertThat(registry.counter("cardflow.authorizations.replayed").count()).isEqualTo(replays + 1);
        // Tests have no fraud-service, so both scorings went through the fallback path
        assertThat(timerCount("fallback") + timerCount("circuit_open")).isEqualTo(fallbackScores + 2);
        // Relay is off in this test, so the two approval events are still waiting
        assertThat(registry.get("cardflow.outbox.backlog").gauge().value()).isGreaterThanOrEqualTo(2);
        assertThat(registry.get("cardflow.fraud.circuit.open").gauge().value()).isIn(0.0, 1.0);
    }

    private double count(String name, String tag, String value) {
        return registry.find(name).tag(tag, value).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private long timerCount(String outcome) {
        var t = registry.find("cardflow.fraud.score").tag("outcome", outcome).timer();
        return t == null ? 0 : t.count();
    }
}
