package com.cardflow.ledger.events;

/** An event that can never succeed (malformed, wrong type/version); sent straight to the DLT, no retries. */
public class UnprocessableEventException extends RuntimeException {

    public UnprocessableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
