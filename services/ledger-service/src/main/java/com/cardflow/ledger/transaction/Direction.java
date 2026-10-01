package com.cardflow.ledger.transaction;

/** Which side of the ledger an entry is on. Every transaction has equal totals on both sides. */
public enum Direction {
    DEBIT,
    CREDIT
}
