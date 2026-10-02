package com.cardflow.ledger.account;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardflow.ledger.common.NotFoundException;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;

    public AccountService(AccountRepository accounts, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
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

    /**
     * Returns the account linked to externalRef, creating it if needed.
     * ON CONFLICT makes this safe if two consumers create the same account at once.
     */
    @Transactional
    public UUID findOrCreateByExternalRef(String externalRef, String name, AccountType type, String currency) {
        jdbc.update("""
                INSERT INTO accounts (id, name, type, currency, external_ref)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (external_ref) DO NOTHING""",
                UUID.randomUUID(), name, type.name(), currency, externalRef);
        return jdbc.queryForObject("SELECT id FROM accounts WHERE external_ref = ?", UUID.class, externalRef);
    }
}
