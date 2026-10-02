package com.cardflow.ledger.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.cardflow.ledger.TestcontainersConfiguration;
import com.cardflow.ledger.events.TransactionAuthorizedEvent.Payload;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerMetricsTest {

    @Autowired
    AuthorizedTransactionPoster poster;

    @Autowired
    MeterRegistry registry;

    @Test
    void postedAndDuplicateEventsAreCounted() {
        double posted = registry.counter("cardflow.ledger.events", "result", "posted").count();
        double duplicates = registry.counter("cardflow.ledger.events", "result", "duplicate").count();
        var event = new TransactionAuthorizedEvent(UUID.randomUUID(), TransactionAuthorizedEvent.TYPE, 1, Instant.now(),
                "t", new Payload(UUID.randomUUID(), UUID.randomUUID(), "4242", "m1", "Shop", "5411", 500, "USD"));

        poster.apply(event);
        poster.apply(event);

        assertThat(registry.counter("cardflow.ledger.events", "result", "posted").count()).isEqualTo(posted + 1);
        assertThat(registry.counter("cardflow.ledger.events", "result", "duplicate").count()).isEqualTo(duplicates + 1);
    }
}
