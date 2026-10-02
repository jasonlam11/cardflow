package com.cardflow.authorization.authorization;

import com.cardflow.authorization.card.CardAccount;
import com.cardflow.authorization.card.CardStatus;

/**
 * Pure decision logic: no database, no Spring, so it's trivial to unit test.
 * Checks run in order and the first failure wins.
 */
public final class AuthorizationRules {

    private AuthorizationRules() {
    }

    /**
     * @param card            the card, or null if the request named an unknown card
     * @param availableMinor  credit limit minus approved spend, before this charge
     */
    public static Decision decide(CardAccount card, long availableMinor, long amountMinor, String currency) {
        if (card == null) {
            return Decision.decline(DeclineReason.CARD_NOT_FOUND);
        }
        if (card.getStatus() != CardStatus.ACTIVE) {
            return Decision.decline(DeclineReason.CARD_NOT_ACTIVE);
        }
        if (!card.getCurrency().equals(currency)) {
            return Decision.decline(DeclineReason.CURRENCY_MISMATCH);
        }
        if (amountMinor > availableMinor) {
            return Decision.decline(DeclineReason.INSUFFICIENT_CREDIT);
        }
        // Phase 3: fraud-service score is checked here
        return Decision.approve();
    }
}
