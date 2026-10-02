package com.cardflow.authorization.outbox;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Appends an event to the outbox table. MANDATORY propagation means this
 * fails loudly if called outside a transaction: the whole point is that the
 * event commits (or rolls back) together with the business change.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(EventEnvelope<?> event, UUID aggregateId) {
        jdbc.update("""
                INSERT INTO outbox_events (id, event_type, aggregate_id, payload, correlation_id, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)""",
                event.eventId(), event.eventType(), aggregateId, json.writeValueAsString(event),
                event.correlationId(), java.sql.Timestamp.from(event.occurredAt()));
    }
}
