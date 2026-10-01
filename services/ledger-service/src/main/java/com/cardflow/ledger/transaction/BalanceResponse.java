package com.cardflow.ledger.transaction;

import java.util.UUID;

import com.cardflow.ledger.account.AccountType;

/** Balance in minor units, plus the totals it was derived from so it can be audited. */
public record BalanceResponse(UUID accountId, AccountType type, String currency, long balanceMinor,
        long totalDebitsMinor, long totalCreditsMinor) {
}
