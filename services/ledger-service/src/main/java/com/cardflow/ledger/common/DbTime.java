package com.cardflow.ledger.common;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Timestamps at the precision Postgres stores (microseconds).
 * Linux clocks give nanoseconds; without truncating, a value returned right
 * after insert differs from the same value read back later (e.g. an
 * idempotent replay would not match the original response).
 */
public final class DbTime {

    private DbTime() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static Instant truncate(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
