package com.cardflow.ledger.transaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class TransactionDtos {

    /** Upper bound per entry ($10 billion in cents); keeps sums far from long overflow. */
    static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L;

    private TransactionDtos() {
    }

    public record PostTransactionRequest(
            @NotBlank @Size(max = 500) String description,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code, e.g. USD") String currency,
            Instant occurredAt,
            @NotNull @Size(min = 2, max = 100, message = "must contain between 2 and 100 entries") List<@Valid @NotNull EntryRequest> entries) {
    }

    public record EntryRequest(
            @NotNull UUID accountId,
            @NotNull Direction direction,
            @Positive @Max(MAX_AMOUNT_MINOR) long amountMinor) {
    }

    public record TransactionResponse(UUID id, String description, Instant occurredAt, Instant createdAt,
            List<EntryResponse> entries) {

        static TransactionResponse from(LedgerTransaction t) {
            return new TransactionResponse(t.getId(), t.getDescription(), t.getOccurredAt(), t.getCreatedAt(),
                    t.getEntries().stream().map(EntryResponse::from).toList());
        }
    }

    public record EntryResponse(UUID id, UUID accountId, Direction direction, long amountMinor, String currency) {

        static EntryResponse from(LedgerEntry e) {
            return new EntryResponse(e.getId(), e.getAccountId(), e.getDirection(), e.getAmountMinor(), e.getCurrency());
        }
    }
}
