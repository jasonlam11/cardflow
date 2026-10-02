package com.cardflow.authorization.authorization;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.cardflow.authorization.fraud.FraudAssessment.FraudReason;
import com.cardflow.authorization.fraud.FraudBand;
import com.cardflow.authorization.fraud.ScoredBy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class AuthorizationDtos {

    private AuthorizationDtos() {
    }

    public enum Channel {
        CARD_PRESENT,
        ECOMMERCE
    }

    public record MerchantLocation(
            @DecimalMin("-90") @DecimalMax("90") double lat,
            @DecimalMin("-180") @DecimalMax("180") double lon,
            @Pattern(regexp = "^[A-Z]{2}$", message = "must be a 2-letter ISO 3166 country code") String country) {
    }

    /**
     * channel, merchantLocation and occurredAt are optional (added in Phase 3),
     * so Phase 2 clients keep working unchanged.
     */
    public record AuthorizationRequest(
            @NotNull UUID cardId,
            @NotBlank @Size(max = 64) String merchantId,
            @NotBlank @Size(max = 200) String merchantName,
            @NotNull @Pattern(regexp = "^[0-9]{4}$", message = "must be a 4-digit merchant category code") String mcc,
            @Positive @Max(1_000_000_000_000L) long amountMinor,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code, e.g. USD") String currency,
            Channel channel,
            @Valid MerchantLocation merchantLocation,
            Instant occurredAt) {

        /** Phase 2 shape, kept for tests and callers that don't send the new fields. */
        public AuthorizationRequest(UUID cardId, String merchantId, String merchantName, String mcc, long amountMinor,
                String currency) {
            this(cardId, merchantId, merchantName, mcc, amountMinor, currency, null, null, null);
        }
    }

    public record FraudSummary(Double score, FraudBand band, List<FraudReason> reasons, ScoredBy scoredBy,
            String modelVersion) {
    }

    public record AuthorizationResponse(UUID id, AuthorizationStatus status, DeclineReason declineReason,
            UUID cardId, String merchantId, String merchantName, String mcc, long amountMinor, String currency,
            FraudSummary fraud, Instant createdAt) {

        public static AuthorizationResponse from(Authorization a) {
            FraudSummary fraud = a.getScoredBy() == null ? null
                    : new FraudSummary(a.getFraudScore(),
                            a.getFraudBand(), a.getFraudReasons() == null ? List.of() : a.getFraudReasons(),
                            a.getScoredBy(), a.getModelVersion());
            return new AuthorizationResponse(a.getId(), a.getStatus(), a.getDeclineReason(), a.getCardAccountId(),
                    a.getMerchantId(), a.getMerchantName(), a.getMcc(), a.getAmountMinor(), a.getCurrency(), fraud,
                    a.getCreatedAt());
        }
    }
}
