package com.cardflow.ledger.account;

/**
 * Accounting category of an account, from the card issuer's point of view.
 *
 * <ul>
 *   <li>ASSET: what others owe us, e.g. a cardholder's receivable balance</li>
 *   <li>LIABILITY: what we owe others, e.g. money due to a merchant</li>
 *   <li>REVENUE: income we earn, e.g. interchange fees</li>
 *   <li>EXPENSE: costs we pay, e.g. rewards paid out</li>
 * </ul>
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    REVENUE,
    EXPENSE
}
