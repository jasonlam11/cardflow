package com.cardflow.ledger.transaction;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.cardflow.ledger.account.Account;
import com.cardflow.ledger.account.AccountType;
import com.cardflow.ledger.transaction.TransactionDtos.EntryRequest;
import com.cardflow.ledger.transaction.TransactionDtos.PostTransactionRequest;

class PostingValidatorTest {

    final PostingValidator validator = new PostingValidator();
    final Account cardholder = new Account("Alice receivable", AccountType.ASSET, "USD");
    final Account merchant = new Account("Coffee Shop payable", AccountType.LIABILITY, "USD");
    final Account euroMerchant = new Account("Paris Cafe payable", AccountType.LIABILITY, "EUR");
    final Map<UUID, Account> known = Map.of(
            cardholder.getId(), cardholder, merchant.getId(), merchant, euroMerchant.getId(), euroMerchant);

    @Test
    void acceptsBalancedTransaction() {
        var request = usd(debit(cardholder, 4250), credit(merchant, 4250));
        assertThatCode(() -> validator.validate(request, known)).doesNotThrowAnyException();
    }

    @Test
    void acceptsSplitAcrossMoreThanTwoEntries() {
        var request = usd(debit(cardholder, 1000), credit(merchant, 700), credit(merchant, 300));
        assertThatCode(() -> validator.validate(request, known)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnbalancedTransaction() {
        var request = usd(debit(cardholder, 4250), credit(merchant, 4000));
        assertThatThrownBy(() -> validator.validate(request, known))
                .isInstanceOf(InvalidPostingException.class)
                .hasMessage("Transaction is unbalanced: debits 4250 != credits 4000");
    }

    @Test
    void rejectsUnknownAccount() {
        UUID ghost = UUID.randomUUID();
        var request = usd(new EntryRequest(ghost, Direction.DEBIT, 100), credit(merchant, 100));
        assertThatThrownBy(() -> validator.validate(request, known))
                .isInstanceOf(InvalidPostingException.class)
                .hasMessageContaining(ghost + " does not exist");
    }

    @Test
    void rejectsAccountInDifferentCurrency() {
        var request = usd(debit(cardholder, 100), credit(euroMerchant, 100));
        assertThatThrownBy(() -> validator.validate(request, known))
                .isInstanceOf(InvalidPostingException.class)
                .hasMessageContaining("is in EUR but the transaction is in USD");
    }

    @Test
    void rejectsSumsThatWouldOverflow() {
        var request = usd(debit(cardholder, Long.MAX_VALUE), debit(cardholder, 1), credit(merchant, 1));
        assertThatThrownBy(() -> validator.validate(request, known)).isInstanceOf(ArithmeticException.class);
    }

    private static PostTransactionRequest usd(EntryRequest... entries) {
        return new PostTransactionRequest("test", "USD", null, List.of(entries));
    }

    private static EntryRequest debit(Account a, long amount) {
        return new EntryRequest(a.getId(), Direction.DEBIT, amount);
    }

    private static EntryRequest credit(Account a, long amount) {
        return new EntryRequest(a.getId(), Direction.CREDIT, amount);
    }
}
