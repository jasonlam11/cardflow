package com.cardflow.ledger.transaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import com.cardflow.ledger.common.DbTime;

/**
 * A balanced group of ledger entries recorded together (a "journal entry").
 * Named LedgerTransaction to avoid confusion with database transactions.
 * Immutable once posted: the database rejects UPDATE and DELETE.
 */
@Entity
@Table(name = "transactions")
public class LedgerTransaction implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "merchant_id", length = 64, updatable = false)
    private String merchantId;

    @Column(name = "merchant_name", length = 200, updatable = false)
    private String merchantName;

    @Column(length = 4, updatable = false)
    private String mcc;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    @OrderBy("direction DESC, amountMinor DESC")
    private List<LedgerEntry> entries = new ArrayList<>();

    protected LedgerTransaction() {
    }

    public LedgerTransaction(String description, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.description = description;
        this.occurredAt = occurredAt;
        this.createdAt = DbTime.now();
    }

    /** Merchant context for card charges (set once, before saving). */
    public void setMerchant(String merchantId, String merchantName, String mcc) {
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.mcc = mcc;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public String getMcc() {
        return mcc;
    }

    public void addEntry(UUID accountId, Direction direction, long amountMinor, String currency) {
        entries.add(new LedgerEntry(this, accountId, direction, amountMinor, currency));
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

    public String getDescription() {
        return description;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
