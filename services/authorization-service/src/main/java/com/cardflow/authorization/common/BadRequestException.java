package com.cardflow.authorization.common;

/** A request that passed field validation but makes no sense (e.g. a timestamp in the future); HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
