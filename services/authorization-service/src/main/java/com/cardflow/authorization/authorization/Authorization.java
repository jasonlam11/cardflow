package com.cardflow.authorization.authorization;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import com.cardflow.authorization.authorization.AuthorizationDtos.AuthorizationRequest;
import com.cardflow.authorization.common.DbTime;
import com.cardflow.authorization.fraud.FraudAssessment;
import com.cardflow.authorization.fraud.FraudAssessment.FraudReason;
import com.cardflow.authorization.fraud.FraudBand;
import com.cardflow.authorization.fraud.ScoredBy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
/** The recorded outcome of one charge request. Immutable once written. */
@Entity
@Table(name = "authorizations")
public class Authorization implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "card_account_id", updatable = false)
    private UUID cardAccountId;

    @Column(name = "merchant_id", nullable = false, length = 64, updatable = false)
    private String merchantId;

    @Column(name = "merchant_name", nullable = false, length = 200, updatable = false)
    private String merchantName;

    @Column(nullable = false, length = 4, updatable = false)
    private String mcc;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private AuthorizationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "decline_reason", length = 40, updatable = false)
    private DeclineReason declineReason;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(length = 16, updatable = false)
    private String channel;

    @Column(name = "merchant_lat", updatable = false)
    private Double merchantLat;

    @Column(name = "merchant_lon", updatable = false)
    private Double merchantLon;

    @Column(name = "merchant_country", length = 2, updatable = false)
    private String merchantCountry;

    @Column(name = "occurred_at", updatable = false)
    private Instant occurredAt;

    @Column(name = "fraud_score", updatable = false)
    private Double fraudScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "fraud_band", length = 8, updatable = false)
    private FraudBand fraudBand;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fraud_reasons", updatable = false)
    private List<FraudReason> fraudReasons;

    @Enumerated(EnumType.STRING)
    @Column(name = "scored_by", length = 16, updatable = false)
    private ScoredBy scoredBy;

    @Column(name = "model_version", length = 40, updatable = false)
    private String modelVersion;

    @Transient
    private boolean isNew = true;

    protected Authorization() {
    }

    public Authorization(String idempotencyKey, String requestHash, UUID cardAccountId, AuthorizationRequest req,
            Decision decision, FraudAssessment fraud, String correlationId) {
        this(idempotencyKey, requestHash, cardAccountId, req.merchantId(), req.merchantName(), req.mcc(),
                req.amountMinor(), req.currency(), decision, correlationId);
        this.channel = req.channel() == null ? null : req.channel().name();
        if (req.merchantLocation() != null) {
            this.merchantLat = req.merchantLocation().lat();
            this.merchantLon = req.merchantLocation().lon();
            this.merchantCountry = req.merchantLocation().country();
        }
        this.occurredAt = DbTime.truncate(req.occurredAt());
        this.fraudScore = fraud.score();
        this.fraudBand = fraud.band();
        this.fraudReasons = fraud.reasons();
        this.scoredBy = fraud.scoredBy();
        this.modelVersion = fraud.modelVersion();
    }

    public Authorization(String idempotencyKey, String requestHash, UUID cardAccountId, String merchantId,
            String merchantName, String mcc, long amountMinor, String currency, Decision decision,
            String correlationId) {
        this.id = UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.cardAccountId = cardAccountId;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.mcc = mcc;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = decision.status();
        this.declineReason = decision.reason();
        this.correlationId = correlationId;
        this.createdAt = DbTime.now();
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getCardAccountId() {
        return cardAccountId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public String getMcc() {
        return mcc;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public AuthorizationStatus getStatus() {
        return status;
    }

    public DeclineReason getDeclineReason() {
        return declineReason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getChannel() {
        return channel;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Double getFraudScore() {
        return fraudScore;
    }

    public FraudBand getFraudBand() {
        return fraudBand;
    }

    public List<FraudReason> getFraudReasons() {
        return fraudReasons;
    }

    public ScoredBy getScoredBy() {
        return scoredBy;
    }

    public String getModelVersion() {
        return modelVersion;
    }
}
