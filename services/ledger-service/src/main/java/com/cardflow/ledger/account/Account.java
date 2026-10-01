package com.cardflow.ledger.account;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A ledger account. There is deliberately no balance field: balances are
 * always derived from ledger entries so they can never drift out of sync.
 */
@Entity
@Table(name = "accounts")
public class Account implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA; not for application use. */
    protected Account() {
    }

    public Account(String name, AccountType type, String currency) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.type = type;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    /**
     * IDs are assigned in Java, so Spring Data can't infer "new" from a null id.
     * Without this, save() would merge (SELECT then UPDATE) instead of INSERT.
     */
    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
