package com.cardflow.ledger.transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cardflow.ledger.account.Account;
import com.cardflow.ledger.account.AccountService;
import com.cardflow.ledger.common.PageResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Read-side views of a single account's ledger. */
@RestController
@RequestMapping("/accounts/{accountId}")
public class AccountLedgerController {

    private final AccountService accountService;
    private final LedgerEntryRepository entries;
    private final LedgerQueries queries;

    public AccountLedgerController(AccountService accountService, LedgerEntryRepository entries, LedgerQueries queries) {
        this.accountService = accountService;
        this.entries = entries;
        this.queries = queries;
    }

    /** Derived from the entries every time; there is no stored balance to drift out of sync. */
    @GetMapping("/balance")
    @Transactional(readOnly = true)
    public BalanceResponse balance(@PathVariable UUID accountId) {
        Account account = accountService.get(accountId);
        DirectionTotals totals = entries.totalsFor(accountId);
        return new BalanceResponse(account.getId(), account.getType(), account.getCurrency(),
                BalanceCalculator.calculate(account.getType(), totals), totals.debits(), totals.credits());
    }

    @GetMapping("/transactions")
    @Transactional(readOnly = true)
    public PageResponse<AccountActivity> history(@PathVariable UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "RECENT") LedgerQueries.Sort sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        accountService.get(accountId); // 404 if the account doesn't exist
        return queries.history(accountId, from, to, sort, page, size);
    }

    /** Spending per merchant category code in [from, to] (inclusive days, UTC), largest first. */
    @GetMapping("/spending")
    @Transactional(readOnly = true)
    public List<LedgerQueries.CategorySpend> spending(@PathVariable UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        accountService.get(accountId);
        return queries.spendingByCategory(accountId, from, to);
    }
}
