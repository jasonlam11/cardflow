package com.cardflow.authorization.card;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
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
 * A synthetic credit card account. There is no card number anywhere: just a
 * random token (what a card network would send us) and the last 4 digits for display.
 */
@Entity
@Table(name = "card_accounts")
public class CardAccount implements Persistable<UUID> {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    private UUID id;

    @Column(name = "card_token", nullable = false, unique = true, length = 40, updatable = false)
    private String cardToken;

    @Column(nullable = false, length = 4, updatable = false)
    private String last4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CardStatus status;

    @Column(name = "credit_limit_minor", nullable = false)
    private long creditLimitMinor;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected CardAccount() {
    }

    public CardAccount(long creditLimitMinor, String currency) {
        byte[] token = new byte[12];
        RANDOM.nextBytes(token);
        this.id = UUID.randomUUID();
        this.cardToken = "tok_" + HexFormat.of().formatHex(token);
        this.last4 = "%04d".formatted(RANDOM.nextInt(10_000));
        this.status = CardStatus.ACTIVE;
        this.creditLimitMinor = creditLimitMinor;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    public void changeStatus(CardStatus newStatus) {
        this.status = newStatus;
    }

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

    public String getCardToken() {
        return cardToken;
    }

    public String getLast4() {
        return last4;
    }

    public CardStatus getStatus() {
        return status;
    }

    public long getCreditLimitMinor() {
        return creditLimitMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
