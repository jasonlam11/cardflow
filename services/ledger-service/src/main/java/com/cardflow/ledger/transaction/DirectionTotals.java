package com.cardflow.ledger.transaction;

/** Sum of all debit entries and all credit entries for one account, in minor units. */
public record DirectionTotals(long debits, long credits) {
}
