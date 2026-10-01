package com.cardflow.ledger.transaction;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.cardflow.ledger.account.Account;
import com.cardflow.ledger.transaction.TransactionDtos.EntryRequest;
import com.cardflow.ledger.transaction.TransactionDtos.PostTransactionRequest;

/**
 * Checks double-entry rules before anything is written, so callers get a clear
 * error. The database enforces the same rules as a safety net (see V1__init.sql).
 * Field-level checks (non-null, positive, entry count) are done by Bean Validation.
 */
@Component
public class PostingValidator {

    /**
     * @param accounts the accounts referenced by the request, keyed by id
     * @throws InvalidPostingException if any rule is broken
     */
    public void validate(PostTransactionRequest request, Map<UUID, Account> accounts) {
        long debits = 0;
        long credits = 0;

        for (EntryRequest entry : request.entries()) {
            Account account = accounts.get(entry.accountId());
            if (account == null) {
                throw new InvalidPostingException("Account " + entry.accountId() + " does not exist");
            }
            if (!account.getCurrency().equals(request.currency())) {
                throw new InvalidPostingException("Account " + account.getId() + " is in " + account.getCurrency()
                        + " but the transaction is in " + request.currency());
            }
            if (entry.direction() == Direction.DEBIT) {
                debits = Math.addExact(debits, entry.amountMinor());
            } else {
                credits = Math.addExact(credits, entry.amountMinor());
            }
        }

        if (debits != credits) {
            throw new InvalidPostingException(
                    "Transaction is unbalanced: debits " + debits + " != credits " + credits);
        }
    }
}
