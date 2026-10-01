package com.cardflow.ledger.transaction;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public AccountLedgerController(AccountService accountService, LedgerEntryRepository entries) {
        this.accountService = accountService;
        this.entries = entries;
    }

    @GetMapping("/transactions")
    @Transactional(readOnly = true)
    public PageResponse<AccountActivity> history(@PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        accountService.get(accountId); // 404 if the account doesn't exist
        return PageResponse.from(entries.findActivity(accountId, PageRequest.of(page, size)), a -> a);
    }
}
