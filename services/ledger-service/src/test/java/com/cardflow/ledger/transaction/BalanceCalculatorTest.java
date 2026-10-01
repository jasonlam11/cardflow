package com.cardflow.ledger.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.cardflow.ledger.account.AccountType;

/** Tests for the YOUR TURN task. These are the spec: make them all pass. */
class BalanceCalculatorTest {

    @Test
    void newAccountHasZeroBalance() {
        for (AccountType type : AccountType.values()) {
            assertThat(BalanceCalculator.calculate(type, new DirectionTotals(0, 0))).as(type.name()).isZero();
        }
    }

    @Test
    void assetGoesUpWithDebits() {
        // Alice charged $42.50 and paid back $10: she still owes us $32.50
        assertThat(BalanceCalculator.calculate(AccountType.ASSET, new DirectionTotals(4250, 1000))).isEqualTo(3250);
    }

    @Test
    void expenseGoesUpWithDebits() {
        assertThat(BalanceCalculator.calculate(AccountType.EXPENSE, new DirectionTotals(500, 0))).isEqualTo(500);
    }

    @Test
    void liabilityGoesUpWithCredits() {
        // We owe the coffee shop $42.50
        assertThat(BalanceCalculator.calculate(AccountType.LIABILITY, new DirectionTotals(0, 4250))).isEqualTo(4250);
    }

    @Test
    void revenueGoesUpWithCredits() {
        assertThat(BalanceCalculator.calculate(AccountType.REVENUE, new DirectionTotals(100, 300))).isEqualTo(200);
    }

    @Test
    void balanceCanGoNegative() {
        // Alice overpaid by $5: we owe her a credit, shown as a negative asset
        assertThat(BalanceCalculator.calculate(AccountType.ASSET, new DirectionTotals(1000, 1500))).isEqualTo(-500);
    }
}
