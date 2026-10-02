package com.cardflow.authorization.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.cardflow.authorization.card.CardAccount;
import com.cardflow.authorization.card.CardStatus;
import com.cardflow.authorization.fraud.FraudAssessment;
import com.cardflow.authorization.fraud.FraudBand;
import com.cardflow.authorization.fraud.ScoredBy;

import java.util.List;

class AuthorizationRulesTest {

    final CardAccount card = new CardAccount(10_000, "USD");
    static final FraudAssessment LOW = fraud(FraudBand.LOW);

    @Test
    void approvesWithinAvailableCredit() {
        assertThat(AuthorizationRules.decide(card, 10_000, 10_000, "USD", LOW)).isEqualTo(Decision.approve());
    }

    @Test
    void declinesUnknownCard() {
        assertThat(AuthorizationRules.decide(null, 0, 100, "USD", LOW).reason()).isEqualTo(DeclineReason.CARD_NOT_FOUND);
    }

    @Test
    void declinesFrozenCard() {
        card.changeStatus(CardStatus.FROZEN);
        assertThat(AuthorizationRules.decide(card, 10_000, 100, "USD", LOW).reason())
                .isEqualTo(DeclineReason.CARD_NOT_ACTIVE);
    }

    @Test
    void declinesWrongCurrency() {
        assertThat(AuthorizationRules.decide(card, 10_000, 100, "EUR", LOW).reason())
                .isEqualTo(DeclineReason.CURRENCY_MISMATCH);
    }

    @Test
    void declinesOneCentOverAvailable() {
        assertThat(AuthorizationRules.decide(card, 10_000, 10_001, "USD", LOW).reason())
                .isEqualTo(DeclineReason.INSUFFICIENT_CREDIT);
    }

    @Test
    void inactiveCheckWinsOverInsufficientCredit() {
        card.changeStatus(CardStatus.CLOSED);
        assertThat(AuthorizationRules.decide(card, 0, 999_999, "USD", LOW).reason())
                .isEqualTo(DeclineReason.CARD_NOT_ACTIVE);
    }

    @Test
    void highFraudBandDeclines() {
        assertThat(AuthorizationRules.decide(card, 10_000, 100, "USD", fraud(FraudBand.HIGH)).reason())
                .isEqualTo(DeclineReason.FRAUD_SUSPECTED);
    }

    @Test
    void reviewBandHoldsForHumanReview() {
        assertThat(AuthorizationRules.decide(card, 10_000, 100, "USD", fraud(FraudBand.REVIEW)))
                .isEqualTo(Decision.review());
    }

    @Test
    void creditCheckRunsBeforeFraud() {
        assertThat(AuthorizationRules.decide(card, 50, 100, "USD", fraud(FraudBand.HIGH)).reason())
                .isEqualTo(DeclineReason.INSUFFICIENT_CREDIT);
    }

    private static FraudAssessment fraud(FraudBand band) {
        return new FraudAssessment(0.5, band, List.of(), ScoredBy.MODEL, "test");
    }
}
