package com.cardflow.ledger.account;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardflow.ledger.account.AccountDtos.AccountResponse;
import com.cardflow.ledger.account.AccountDtos.CreateAccountRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.create(request.name(), request.type(), request.currency());
        return ResponseEntity.created(URI.create("/accounts/" + account.getId()))
                .body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accountService.get(id));
    }
}
