package com.cardflow.ledger.account;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardflow.ledger.common.NotFoundException;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public Account create(String name, AccountType type, String currency) {
        return accounts.save(new Account(name, type, currency));
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> new NotFoundException("Account " + id + " not found"));
    }
}
