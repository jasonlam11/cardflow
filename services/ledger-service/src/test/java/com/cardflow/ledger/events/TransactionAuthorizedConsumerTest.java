package com.cardflow.ledger.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import com.cardflow.ledger.TestcontainersConfiguration;

/** Real Kafka + real Postgres: events in, ledger entries out. */
@SpringBootTest(properties = {"cardflow.events.topic=consumer-test",
        "spring.kafka.consumer.group-id=ledger-consumer-test"})
@Import({TestcontainersConfiguration.class, TransactionAuthorizedConsumerTest.Topics.class})
class TransactionAuthorizedConsumerTest {

    static final String TOPIC = "consumer-test";

    /**
     * Create topics before the listener starts (KafkaAdmin does this at startup).
     * Otherwise the consumer subscribes to a missing topic and may not notice it
     * appear until its next metadata refresh, minutes later.
     */
    @TestConfiguration
    static class Topics {
        @Bean
        NewTopic consumerTestTopic() {
            return new NewTopic(TOPIC, 1, (short) 1);
        }

        @Bean
        NewTopic consumerTestDlt() {
            return new NewTopic(TOPIC + ".DLT", 1, (short) 1);
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaConnectionDetails kafkaConnection;

    @Test
    void postsDoubleEntryForAuthorizedCharge() throws Exception {
        UUID card = UUID.randomUUID();
        String merchant = "m-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        send(card, event(eventId, card, merchant, 4250));

        awaitProcessed(eventId);
        assertThat(balance("card:" + card)).isEqualTo(4250);      // ASSET: cardholder owes 42.50
        assertThat(balance("merchant:" + merchant)).isEqualTo(4250); // LIABILITY: we owe merchant 42.50
        assertThat(jdbc.queryForObject("SELECT type FROM accounts WHERE external_ref = ?", String.class,
                "card:" + card)).isEqualTo("ASSET");
    }

    @Test
    void duplicateDeliveriesPostExactlyOnce() throws Exception {
        UUID card = UUID.randomUUID();
        String merchant = "m-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String json = event(eventId, card, merchant, 1000);

        // Kafka is at-least-once: simulate the same event arriving three times
        send(card, json);
        send(card, json);
        send(card, json);
        // A later, different event on the same card proves the consumer moved past the duplicates
        UUID marker = UUID.randomUUID();
        send(card, event(marker, card, merchant, 1));

        awaitProcessed(marker);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Long.class,
                eventId)).isEqualTo(1);
        assertThat(balance("card:" + card)).isEqualTo(1001);
    }

    @Test
    void sameMerchantAcrossCardsSharesOneAccount() throws Exception {
        String merchant = "m-" + UUID.randomUUID();
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        send(UUID.randomUUID(), event(e1, UUID.randomUUID(), merchant, 300));
        send(UUID.randomUUID(), event(e2, UUID.randomUUID(), merchant, 700));

        awaitProcessed(e1);
        awaitProcessed(e2);
        assertThat(balance("merchant:" + merchant)).isEqualTo(1000);
    }

    @Test
    void malformedEventGoesToDeadLetterTopic() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        kafka.send(TOPIC, key, "{ this is not json").get();

        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var keys = new java.util.ArrayList<String>();
                consumer.poll(Duration.ofMillis(500)).forEach(r -> keys.add(r.key()));
                assertThat(keys).contains(key);
            });
        }
    }

    private void send(UUID key, String json) throws Exception {
        kafka.send(new ProducerRecord<>(TOPIC, key.toString(), json)).get();
    }

    private void awaitProcessed(UUID eventId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Long.class, eventId) == 1);
    }

    private long balance(String externalRef) {
        // Derived exactly like the API: debits - credits for ASSET, credits - debits for LIABILITY
        return jdbc.queryForObject("""
                SELECT CASE a.type WHEN 'ASSET' THEN 1 ELSE -1 END
                       * COALESCE(SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)
                  FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
                 WHERE a.external_ref = ?
                 GROUP BY a.type""", Long.class, externalRef);
    }

    private static String event(UUID eventId, UUID card, String merchantId, long amount) {
        return """
                {"eventId": "%s", "eventType": "transaction.authorized", "schemaVersion": 1,
                 "occurredAt": "%s", "correlationId": "test-corr",
                 "payload": {"authorizationId": "%s", "cardAccountId": "%s", "cardLast4": "4242",
                             "merchantId": "%s", "merchantName": "Test Merchant", "mcc": "5814",
                             "amountMinor": %d, "currency": "USD", "someFutureField": "ignored"}}"""
                .formatted(eventId, Instant.now(), UUID.randomUUID(), card, merchantId, amount);
    }
}
