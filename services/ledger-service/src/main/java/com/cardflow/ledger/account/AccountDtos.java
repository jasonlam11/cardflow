package com.cardflow.ledger.account;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies for the accounts API. Entities are never exposed directly. */
public final class AccountDtos {

    private AccountDtos() {
    }

    public record CreateAccountRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull AccountType type,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code, e.g. USD") String currency) {
    }

    public record AccountResponse(UUID id, String name, AccountType type, String currency, Instant createdAt) {

        static AccountResponse from(Account a) {
            return new AccountResponse(a.getId(), a.getName(), a.getType(), a.getCurrency(), a.getCreatedAt());
        }
    }
}
