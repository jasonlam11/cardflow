package com.cardflow.authorization.common;

/** The request is valid but conflicts with the resource's current state; HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
