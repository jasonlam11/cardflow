package com.cardflow.ledger.transaction;

import java.time.Instant;
import java.util.UUID;

/** One line on an account statement: this account's side of a transaction. */
public record AccountActivity(UUID transactionId, String description, Instant occurredAt, Direction direction,
        long amountMinor, String currency) {
}
