package com.cardflow.authorization.authorization;

/** The same Idempotency-Key was reused with a different request body; mapped to HTTP 422. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request");
    }
}
