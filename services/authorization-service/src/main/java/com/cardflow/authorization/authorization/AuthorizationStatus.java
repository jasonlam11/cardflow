package com.cardflow.authorization.authorization;

public enum AuthorizationStatus {
    APPROVED,
    DECLINED,
    /** Borderline fraud score: credit is held and a human analyst decides (Phase 4). */
    PENDING_REVIEW
}
