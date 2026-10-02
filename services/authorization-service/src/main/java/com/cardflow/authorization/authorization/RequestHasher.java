package com.cardflow.authorization.authorization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.cardflow.authorization.authorization.AuthorizationDtos.AuthorizationRequest;

/**
 * Fingerprint of a request's meaningful fields, stored with its idempotency key.
 * A retry must have the same fingerprint; the same key with a different body is a client bug.
 */
public final class RequestHasher {

    private RequestHasher() {
    }

    public static String hash(AuthorizationRequest r) {
        // Unit separator (U+001F) can't appear in validated fields, so values can't run together
        String canonical = String.join("\u001F", r.cardId().toString(), r.merchantId(), r.merchantName(), r.mcc(),
                Long.toString(r.amountMinor()), r.currency());
        // Phase 3 fields are appended only when present, so requests without them
        // hash exactly as they did in Phase 2 (stored keys stay valid)
        if (r.channel() != null || r.merchantLocation() != null || r.occurredAt() != null) {
            var loc = r.merchantLocation();
            canonical = String.join("\u001F", canonical, String.valueOf(r.channel()),
                    loc == null ? "null" : loc.lat() + "," + loc.lon() + "," + loc.country(),
                    String.valueOf(r.occurredAt()));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
