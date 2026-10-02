package com.cardflow.authorization.fraud;

public enum ScoredBy {
    /** fraud-service answered in time */
    MODEL,
    /** fraud-service was slow, down, or the circuit was open: local rules decided */
    RULES_FALLBACK,
    /** card unknown or inactive, so there was nothing to score */
    NOT_SCORED
}
