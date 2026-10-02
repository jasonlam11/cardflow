package com.cardflow.authorization.authorization;

import com.cardflow.authorization.card.CardAccount;
import com.cardflow.authorization.card.CardStatus;
import com.cardflow.authorization.fraud.FraudAssessment;
import com.cardflow.authorization.fraud.FraudBand;

/**
 * Pure decision logic: no database, no Spring, so it's trivial to unit test.
 * Checks run in order and the first failure wins.
 */
public final class AuthorizationRules {

    private AuthorizationRules() {
    }

    /**
     * @param card            the card, or null if the request named an unknown card
     * @param availableMinor  credit limit minus approved and held spend, before this charge
     * @param fraud           the fraud assessment (from the model or the fallback rules)
     */
    public static Decision decide(CardAccount card, long availableMinor, long amountMinor, String currency,
            FraudAssessment fraud) {
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
        if (fraud.band() == FraudBand.HIGH) {
            return Decision.decline(DeclineReason.FRAUD_SUSPECTED);
        }
        if (fraud.band() == FraudBand.REVIEW) {
            // The model never has the final word on borderline cases
            return Decision.review();
        }
        return Decision.approve();
    }
}
