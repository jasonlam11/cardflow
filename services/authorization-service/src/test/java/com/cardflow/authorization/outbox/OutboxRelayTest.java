package com.cardflow.authorization.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.cardflow.authorization.TestcontainersConfiguration;
import com.cardflow.authorization.authorization.AuthorizationDtos.AuthorizationRequest;
import com.cardflow.authorization.authorization.AuthorizationService;
import com.cardflow.authorization.card.CardService;
import com.jayway.jsonpath.JsonPath;

/** Real Postgres + real Kafka. The scheduler is off so each test drives the relay itself. */
@SpringBootTest(properties = {"cardflow.outbox.enabled=false", "cardflow.outbox.topic=relay-test"})
@Import({TestcontainersConfiguration.class, OutboxRelayTest.Topics.class})
class OutboxRelayTest {

    /** Create the topic at startup so the first send doesn't race topic auto-creation. */
    @TestConfiguration
    static class Topics {
        @Bean
        NewTopic relayTestTopic() {
            return new NewTopic("relay-test", 1, (short) 1);
        }
    }

    @Autowired
    OutboxRelay relay;

    @Autowired
    AuthorizationService authorizations;

    @Autowired
    CardService cards;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    OutboxProperties props;

    // The Testcontainers broker address (not spring.kafka.bootstrap-servers, which @ServiceConnection bypasses)
    @Autowired
    KafkaConnectionDetails kafkaConnection;

    @BeforeEach
    void drainBacklog() {
        // Other test classes share this database; start from an empty outbox backlog
        while (relay.publishBatch() > 0) {
        }
    }

    @Test
    void publishesApprovedAuthorizationWithKeyAndHeaders() {
        UUID card = cards.create(10_000, "USD").getId();
        UUID authId = approve(card, 4250);

        assertThat(relay.publishBatch()).isEqualTo(1);

        ConsumerRecord<String, String> record = consumeUntil(r -> r.value().contains(authId.toString()));
        assertThat(record.key()).isEqualTo(card.toString());
        assertThat((String) JsonPath.read(record.value(), "$.eventType")).isEqualTo("transaction.authorized");
        assertThat(header(record, "eventType")).isEqualTo("transaction.authorized");
        assertThat(header(record, "eventId")).isEqualTo(JsonPath.read(record.value(), "$.eventId"));

        assertThat(jdbc.queryForObject("""
                SELECT published_at IS NOT NULL FROM outbox_events
                 WHERE payload->'payload'->>'authorizationId' = ?""", Boolean.class, authId.toString())).isTrue();
        assertThat(relay.publishBatch()).as("nothing left to send").isZero();
    }

    @Test
    void kafkaOutageLosesNothing() {
        UUID card = cards.create(10_000, "USD").getId();
        UUID authId = approve(card, 1500);

        // A relay pointed at a broker that doesn't exist: every send fails
        var deadKafka = new KafkaTemplate<String, String>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500)));
        var brokenRelay = new OutboxRelay(jdbc, tx, deadKafka, props);

        assertThat(brokenRelay.publishBatch()).isZero();
        assertThat(brokenRelay.publishBatch()).isZero();

        var row = jdbc.queryForMap("""
                SELECT published_at, attempts, last_error FROM outbox_events
                 WHERE payload->'payload'->>'authorizationId' = ?""", authId.toString());
        assertThat(row.get("published_at")).as("still waiting to be sent").isNull();
        assertThat((Integer) row.get("attempts")).isEqualTo(2);
        assertThat((String) row.get("last_error")).isNotBlank();

        // Kafka is back: the event goes out, nothing was lost
        assertThat(relay.publishBatch()).isEqualTo(1);
        consumeUntil(r -> r.value().contains(authId.toString()));
        deadKafka.destroy();
    }

    private UUID approve(UUID card, long amount) {
        var req = new AuthorizationRequest(card, "m1", "Coffee", "5814", amount, "USD");
        return authorizations.authorize(UUID.randomUUID().toString(), req).authorization().getId();
    }

    private ConsumerRecord<String, String> consumeUntil(java.util.function.Predicate<ConsumerRecord<String, String>> match) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(props.topic()));
            long deadline = System.currentTimeMillis() + 15_000;
            List<String> seen = new ArrayList<>();
            while (System.currentTimeMillis() < deadline) {
                for (var r : consumer.poll(Duration.ofMillis(500))) {
                    seen.add(r.value());
                    if (match.test(r)) {
                        return r;
                    }
                }
            }
            throw new AssertionError("No matching record on " + props.topic() + "; saw " + seen.size());
        }
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
