package com.cardflow.ledger.events;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cardflow.ledger.account.AccountService;
import com.cardflow.ledger.account.AccountType;
import com.cardflow.ledger.transaction.Direction;
import com.cardflow.ledger.transaction.LedgerTransaction;
import com.cardflow.ledger.transaction.TransactionDtos.EntryRequest;
import com.cardflow.ledger.transaction.TransactionDtos.PostTransactionRequest;
import com.cardflow.ledger.transaction.TransactionService;

/**
 * Applies one authorized charge to the ledger, exactly once:
 * <pre>
 *   DEBIT  card:&lt;id&gt;       (ASSET: the cardholder now owes us)
 *   CREDIT merchant:&lt;id&gt;   (LIABILITY: we now owe the merchant)
 * </pre>
 * The dedupe marker and the posting share one DB transaction, so they commit
 * or roll back together.
 */
@Component
public class AuthorizedTransactionPoster {

    private static final Logger log = LoggerFactory.getLogger(AuthorizedTransactionPoster.class);

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final TransactionService transactions;

    public AuthorizedTransactionPoster(JdbcTemplate jdbc, AccountService accounts, TransactionService transactions) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    /** @return true if posted, false if this event was already applied */
    @Transactional
    public boolean apply(TransactionAuthorizedEvent event) {
        int inserted = jdbc.update(
                "INSERT INTO processed_events (event_id, event_type) VALUES (?, ?) ON CONFLICT DO NOTHING",
                event.eventId(), event.eventType());
        if (inserted == 0) {
            log.info("Skipping duplicate event {}", event.eventId());
            return false;
        }

        var p = event.payload();
        UUID cardholder = accounts.findOrCreateByExternalRef("card:" + p.cardAccountId(),
                "Card ending " + p.cardLast4() + " receivable", AccountType.ASSET, p.currency());
        UUID merchant = accounts.findOrCreateByExternalRef("merchant:" + p.merchantId(),
                p.merchantName() + " payable", AccountType.LIABILITY, p.currency());

        LedgerTransaction txn = transactions.post(new PostTransactionRequest(
                p.merchantName() + " (authorization " + p.authorizationId() + ")", p.currency(), event.occurredAt(),
                List.of(new EntryRequest(cardholder, Direction.DEBIT, p.amountMinor()),
                        new EntryRequest(merchant, Direction.CREDIT, p.amountMinor()))));

        jdbc.update("UPDATE processed_events SET transaction_id = ? WHERE event_id = ?", txn.getId(), event.eventId());
        log.info("Posted event {} as transaction {} ({} {})", event.eventId(), txn.getId(), p.amountMinor(),
                p.currency());
        return true;
    }
}
