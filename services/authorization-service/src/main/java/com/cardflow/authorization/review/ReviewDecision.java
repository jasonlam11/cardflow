package com.cardflow.authorization.review;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import com.cardflow.authorization.authorization.AuthorizationStatus;
import com.cardflow.authorization.common.DbTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/** One analyst decision on one authorization. Append-only (enforced by a DB trigger). */
@Entity
@Table(name = "review_decisions")
public class ReviewDecision implements Persistable<UUID> {

    public enum Outcome {
        APPROVE,
        REJECT
    }

    @Id
    private UUID id;

    @Column(name = "authorization_id", nullable = false, unique = true, updatable = false)
    private UUID authorizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private Outcome decision;

    @Column(nullable = false, length = 100, updatable = false)
    private String analyst;

    @Column(length = 1000, updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 16, updatable = false)
    private AuthorizationStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 16, updatable = false)
    private AuthorizationStatus newStatus;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    @Transient
    private boolean isNew = true;

    protected ReviewDecision() {
    }

    public ReviewDecision(UUID authorizationId, Outcome decision, String analyst, String note,
            AuthorizationStatus previousStatus, AuthorizationStatus newStatus) {
        this.id = UUID.randomUUID();
        this.authorizationId = authorizationId;
        this.decision = decision;
        this.analyst = analyst;
        this.note = note;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.decidedAt = DbTime.now();
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

    public UUID getAuthorizationId() {
        return authorizationId;
    }

    public Outcome getDecision() {
        return decision;
    }

    public String getAnalyst() {
        return analyst;
    }

    public String getNote() {
        return note;
    }

    public AuthorizationStatus getPreviousStatus() {
        return previousStatus;
    }

    public AuthorizationStatus getNewStatus() {
        return newStatus;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
