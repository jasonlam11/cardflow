package com.cardflow.authorization.card;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public final class CardDtos {

    private CardDtos() {
    }

    public record CreateCardRequest(
            @PositiveOrZero @Max(100_000_000_00L) long creditLimitMinor,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code, e.g. USD") String currency) {
    }

    public record ChangeStatusRequest(@NotNull CardStatus status) {
    }

    /** The token is shown so the simulator can use it; there is no card number to leak. */
    public record CardResponse(UUID id, String cardToken, String last4, CardStatus status, long creditLimitMinor,
            long availableCreditMinor, String currency, Instant createdAt) {

        static CardResponse from(CardAccount c, long available) {
            return new CardResponse(c.getId(), c.getCardToken(), c.getLast4(), c.getStatus(),
                    c.getCreditLimitMinor(), available, c.getCurrency(), c.getCreatedAt());
        }
    }
}
