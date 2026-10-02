package com.cardflow.authorization.authorization;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class AuthorizationDtos {

    private AuthorizationDtos() {
    }

    public record AuthorizationRequest(
            @NotNull UUID cardId,
            @NotBlank @Size(max = 64) String merchantId,
            @NotBlank @Size(max = 200) String merchantName,
            @NotNull @Pattern(regexp = "^[0-9]{4}$", message = "must be a 4-digit merchant category code") String mcc,
            @Positive @Max(1_000_000_000_000L) long amountMinor,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code, e.g. USD") String currency) {
    }

    public record AuthorizationResponse(UUID id, AuthorizationStatus status, DeclineReason declineReason,
            UUID cardId, String merchantId, String merchantName, String mcc, long amountMinor, String currency,
            Instant createdAt) {

        public static AuthorizationResponse from(Authorization a) {
            return new AuthorizationResponse(a.getId(), a.getStatus(), a.getDeclineReason(), a.getCardAccountId(),
                    a.getMerchantId(), a.getMerchantName(), a.getMcc(), a.getAmountMinor(), a.getCurrency(),
                    a.getCreatedAt());
        }
    }
}
