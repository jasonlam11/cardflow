package com.cardflow.authorization.outbox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes unsent outbox rows to Kafka, oldest first.
 *
 * Delivery is at-least-once: if we crash after Kafka accepted a message but
 * before marking the row published, it is sent again on restart. Consumers
 * deduplicate on the eventId header, so that's safe.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties props;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
            OutboxProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.props = props;
    }

    private record Row(UUID id, UUID aggregateId, String eventType, String payload, String correlationId) {
    }

    /** @return how many events were published in this batch */
    public int publishBatch() {
        Integer published = tx.execute(status -> {
            // SKIP LOCKED: if another relay instance holds some rows, take the next ones instead of waiting
            List<Row> rows = jdbc.query("""
                    SELECT id, aggregate_id, event_type, payload::text, correlation_id
                      FROM outbox_events
                     WHERE published_at IS NULL
                     ORDER BY created_at
                     LIMIT ?
                       FOR UPDATE SKIP LOCKED""",
                    (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                            rs.getString(4), rs.getString(5)),
                    props.batchSize());
            if (rows.isEmpty()) {
                return 0;
            }

            // Send the whole batch, then wait for each acknowledgement in order
            List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>();
            for (Row row : rows) {
                sends.add(send(row));
            }

            int ok = 0;
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                try {
                    sends.get(i).get(props.sendTimeoutMs(), TimeUnit.MILLISECONDS);
                    jdbc.update("UPDATE outbox_events SET published_at = now(), attempts = attempts + 1 WHERE id = ?",
                            row.id());
                    ok++;
                } catch (Exception e) {
                    // Stop at the first failure so this row is retried before newer ones next time.
                    // Later rows that did reach Kafka will be re-sent: harmless, consumers dedupe.
                    String error = rootMessage(e);
                    jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                            error, row.id());
                    log.warn("Outbox publish failed for event {} ({}); {} rows left for retry", row.id(), error,
                            rows.size() - i);
                    break;
                }
            }
            return ok;
        });
        if (published != null && published > 0) {
            log.info("Published {} outbox events to {}", published, props.topic());
        }
        return published == null ? 0 : published;
    }

    private CompletableFuture<SendResult<String, String>> send(Row row) {
        var record = new ProducerRecord<String, String>(props.topic(), row.aggregateId().toString(), row.payload());
        record.headers().add("eventId", bytes(row.id().toString()));
        record.headers().add("eventType", bytes(row.eventType()));
        if (row.correlationId() != null) {
            record.headers().add("correlationId", bytes(row.correlationId()));
        }
        try {
            return kafka.send(record);
        } catch (Exception e) {
            // send() itself can throw, e.g. when broker metadata can't be fetched in max.block.ms
            return CompletableFuture.failedFuture(e);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getClass().getSimpleName() + ": " + root.getMessage();
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
