package com.cardflow.authorization.authorization;

import java.util.UUID;

/**
 * Payload of the "transaction.authorized" event (schema version 1).
 * Contains only what downstream services need: no card token, no names.
 */
public record TransactionAuthorized(UUID authorizationId, UUID cardAccountId, String cardLast4, String merchantId,
        String merchantName, String mcc, long amountMinor, String currency) {

    public static final String EVENT_TYPE = "transaction.authorized";
    public static final int SCHEMA_VERSION = 1;
}
