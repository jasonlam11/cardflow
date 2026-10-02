package com.cardflow.ledger.transaction;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardflow.ledger.account.Account;
import com.cardflow.ledger.account.AccountRepository;
import com.cardflow.ledger.common.NotFoundException;
import com.cardflow.ledger.transaction.TransactionDtos.EntryRequest;
import com.cardflow.ledger.transaction.TransactionDtos.PostTransactionRequest;
import com.cardflow.ledger.common.DbTime;

@Service
public class TransactionService {

    private final LedgerTransactionRepository transactions;
    private final AccountRepository accounts;
    private final PostingValidator validator;

    public TransactionService(LedgerTransactionRepository transactions, AccountRepository accounts,
            PostingValidator validator) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.validator = validator;
    }

    /** Validates and records a transaction and all its entries atomically. */
    /** Merchant context from a card event; null for direct API postings. */
    public record Merchant(String merchantId, String merchantName, String mcc) {
    }

    @Transactional
    public LedgerTransaction post(PostTransactionRequest request) {
        return post(request, null);
    }

    @Transactional
    public LedgerTransaction post(PostTransactionRequest request, Merchant merchant) {
        Set<UUID> accountIds = request.entries().stream().map(EntryRequest::accountId).collect(Collectors.toSet());
        Map<UUID, Account> referenced = accounts.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        validator.validate(request, referenced);

        Instant occurredAt = request.occurredAt() != null ? DbTime.truncate(request.occurredAt()) : DbTime.now();
        LedgerTransaction txn = new LedgerTransaction(request.description(), occurredAt);
        if (merchant != null) {
            txn.setMerchant(merchant.merchantId(), merchant.merchantName(), merchant.mcc());
        }
        for (EntryRequest e : request.entries()) {
            txn.addEntry(e.accountId(), e.direction(), e.amountMinor(), request.currency());
        }
        // Flush now: callers may run plain JDBC next (e.g. the event consumer), which can't
        // see inserts Hibernate is still holding in memory
        return transactions.saveAndFlush(txn);
    }

    @Transactional(readOnly = true)
    public LedgerTransaction get(UUID id) {
        return transactions.findWithEntriesById(id)
                .orElseThrow(() -> new NotFoundException("Transaction " + id + " not found"));
    }
}
