package com.cardflow.authorization.authorization;

/** Outcome of the authorization rules: approved, held for review, or declined with a reason. */
public record Decision(AuthorizationStatus status, DeclineReason reason) {

    public static Decision approve() {
        return new Decision(AuthorizationStatus.APPROVED, null);
    }

    public static Decision review() {
        return new Decision(AuthorizationStatus.PENDING_REVIEW, null);
    }

    public static Decision decline(DeclineReason reason) {
        return new Decision(AuthorizationStatus.DECLINED, reason);
    }

    public boolean approved() {
        return status == AuthorizationStatus.APPROVED;
    }
}
