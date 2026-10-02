package com.cardflow.authorization.fraud;

import java.time.Instant;

/** Body of POST /score on fraud-service. */
public record ScoreRequest(String requestId, String cardId, long amountMinor, String currency, String mcc,
        String merchantId, String channel, Location merchantLocation, Instant occurredAt) {

    public record Location(double lat, double lon, String country) {
    }
}
