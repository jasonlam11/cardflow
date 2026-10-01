package com.cardflow.authorization.outbox;

import java.time.Instant;
import java.util.UUID;
import com.cardflow.authorization.common.DbTime;

/**
 * Standard wrapper for every event this service publishes. eventId is what
 * consumers use to drop duplicates; schemaVersion lets the payload evolve.
 */
public record EventEnvelope<T>(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
        String correlationId, T payload) {

    public static <T> EventEnvelope<T> of(String eventType, int schemaVersion, String correlationId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, schemaVersion, DbTime.now(), correlationId, payload);
    }
}
