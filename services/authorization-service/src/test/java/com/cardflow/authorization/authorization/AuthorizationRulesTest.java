package com.cardflow.authorization.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.cardflow.authorization.card.CardAccount;
import com.cardflow.authorization.card.CardStatus;

class AuthorizationRulesTest {

    final CardAccount card = new CardAccount(10_000, "USD");

    @Test
    void approvesWithinAvailableCredit() {
        assertThat(AuthorizationRules.decide(card, 10_000, 10_000, "USD")).isEqualTo(Decision.approve());
    }

    @Test
    void declinesUnknownCard() {
        assertThat(AuthorizationRules.decide(null, 0, 100, "USD").reason()).isEqualTo(DeclineReason.CARD_NOT_FOUND);
    }

    @Test
    void declinesFrozenCard() {
        card.changeStatus(CardStatus.FROZEN);
        assertThat(AuthorizationRules.decide(card, 10_000, 100, "USD").reason())
                .isEqualTo(DeclineReason.CARD_NOT_ACTIVE);
    }

    @Test
    void declinesWrongCurrency() {
        assertThat(AuthorizationRules.decide(card, 10_000, 100, "EUR").reason())
                .isEqualTo(DeclineReason.CURRENCY_MISMATCH);
    }

    @Test
    void declinesOneCentOverAvailable() {
        assertThat(AuthorizationRules.decide(card, 10_000, 10_001, "USD").reason())
                .isEqualTo(DeclineReason.INSUFFICIENT_CREDIT);
    }

    @Test
    void inactiveCheckWinsOverInsufficientCredit() {
        card.changeStatus(CardStatus.CLOSED);
        assertThat(AuthorizationRules.decide(card, 0, 999_999, "USD").reason())
                .isEqualTo(DeclineReason.CARD_NOT_ACTIVE);
    }
}
