package com.cardflow.ledger.transaction;

import com.cardflow.ledger.account.AccountType;

/**
 * Turns an account's debit and credit totals into its balance.
 *
 * Every account type has a "normal side", the side that makes it go up:
 * <ul>
 *   <li>ASSET, EXPENSE: debits increase the balance</li>
 *   <li>LIABILITY, REVENUE: credits increase the balance</li>
 * </ul>
 * Example: Alice (ASSET) is charged 4250 and pays back 1000, so her balance is
 * 4250 - 1000 = 3250 ("Alice owes us $32.50"). A negative result means the
 * account is on its opposite side, e.g. Alice overpaid.
 */
public final class BalanceCalculator {

    private BalanceCalculator() {
    }

    public static long calculate(AccountType type, DirectionTotals totals) {
        long debits = totals.debits();
        long credits = totals.credits();

        return switch (type) {
            case ASSET, EXPENSE -> debits - credits;
            case LIABILITY, REVENUE -> credits - debits;
        };
    }
}
