package com.cardflow.ledger.transaction;

/** A posting request breaks a double-entry rule; mapped to HTTP 400. */
public class InvalidPostingException extends RuntimeException {

    public InvalidPostingException(String message) {
        super(message);
    }
}
