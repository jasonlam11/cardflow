package com.cardflow.authorization.authorization;

public enum DeclineReason {
    CARD_NOT_FOUND,
    CARD_NOT_ACTIVE,
    CURRENCY_MISMATCH,
    INSUFFICIENT_CREDIT,
    FRAUD_SUSPECTED
}
