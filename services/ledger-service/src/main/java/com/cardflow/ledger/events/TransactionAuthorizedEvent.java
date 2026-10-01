package com.cardflow.ledger.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The ledger's own copy of the authorization-service event contract
 * (eventType "transaction.authorized", schemaVersion 1). Services share the
 * JSON contract, never Java classes. Unknown fields are ignored, so the
 * producer can add fields without breaking us.
 */
public record TransactionAuthorizedEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
        String correlationId, Payload payload) {

    public static final String TYPE = "transaction.authorized";

    public record Payload(UUID authorizationId, UUID cardAccountId, String cardLast4, String merchantId,
            String merchantName, String mcc, long amountMinor, String currency) {
    }
}
