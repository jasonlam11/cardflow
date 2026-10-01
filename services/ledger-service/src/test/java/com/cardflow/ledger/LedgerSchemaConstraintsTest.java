package com.cardflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the ledger's safety rules are enforced by Postgres itself, using raw
 * SQL that bypasses all Java validation. If application code ever has a bug,
 * the database still refuses to store an unbalanced or edited ledger.
 *
 * Rows can't be deleted (that's the point), so every test uses fresh UUIDs
 * instead of cleaning up.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerSchemaConstraintsTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    TransactionTemplate tx;
    UUID cardholder;
    UUID merchant;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cardholder = insertAccount("ASSET", "USD");
        merchant = insertAccount("LIABILITY", "USD");
    }

    @Test
    void balancedTransactionCommits() {
        assertThatCode(() -> tx.executeWithoutResult(s -> {
            UUID txn = insertTransaction();
            insertEntry(txn, cardholder, "DEBIT", 4250, "USD");
            insertEntry(txn, merchant, "CREDIT", 4250, "USD");
        })).doesNotThrowAnyException();
    }

    @Test
    void unbalancedTransactionIsRejectedAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID txn = insertTransaction();
            insertEntry(txn, cardholder, "DEBIT", 4250, "USD");
            insertEntry(txn, merchant, "CREDIT", 4000, "USD");
        })).hasStackTraceContaining("is unbalanced");
    }

    @Test
    void singleEntryTransactionIsRejected() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID txn = insertTransaction();
            insertEntry(txn, cardholder, "DEBIT", 100, "USD");
        })).hasStackTraceContaining("at least 2 required");
    }

    @Test
    void transactionWithNoEntriesIsRejected() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertTransaction()))
                .hasStackTraceContaining("at least 2 required");
    }

    @Test
    void entryCurrencyMustMatchAccountCurrency() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID txn = insertTransaction();
            insertEntry(txn, cardholder, "DEBIT", 100, "EUR");
            insertEntry(txn, merchant, "CREDIT", 100, "EUR");
        })).hasStackTraceContaining("violates foreign key constraint");
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID txn = insertTransaction();
            insertEntry(txn, cardholder, "DEBIT", 0, "USD");
            insertEntry(txn, merchant, "CREDIT", 0, "USD");
        })).hasStackTraceContaining("amount_minor_check");
    }

    @Test
    void postedEntriesCannotBeUpdatedOrDeleted() {
        UUID txn = tx.execute(s -> {
            UUID t = insertTransaction();
            insertEntry(t, cardholder, "DEBIT", 500, "USD");
            insertEntry(t, merchant, "CREDIT", 500, "USD");
            return t;
        });

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_entries SET amount_minor = 1 WHERE transaction_id = ?", txn))
                .hasStackTraceContaining("append-only: UPDATE is not allowed");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ledger_entries WHERE transaction_id = ?", txn))
                .hasStackTraceContaining("append-only: DELETE is not allowed");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transactions WHERE id = ?", txn))
                .hasStackTraceContaining("append-only: DELETE is not allowed");

        Long total = jdbc.queryForObject(
                "SELECT SUM(amount_minor) FROM ledger_entries WHERE transaction_id = ?", Long.class, txn);
        assertThat(total).isEqualTo(1000);
    }

    private UUID insertAccount(String type, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, name, type, currency) VALUES (?, ?, ?, ?)",
                id, "test " + type, type, currency);
        return id;
    }

    private UUID insertTransaction() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO transactions (id, description, occurred_at) VALUES (?, 'test', now())", id);
        return id;
    }

    private void insertEntry(UUID txn, UUID account, String direction, long amount, String currency) {
        jdbc.update("""
                INSERT INTO ledger_entries (id, transaction_id, account_id, direction, amount_minor, currency)
                VALUES (?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), txn, account, direction, amount, currency);
    }
}
