package com.cardflow.ledger.transaction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.cardflow.ledger.LedgerApiClient;
import com.cardflow.ledger.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountHistoryApiTest {

    @Autowired
    MockMvc mvc;

    LedgerApiClient api;
    String cardholder;
    String merchant;

    @BeforeEach
    void setUp() throws Exception {
        api = new LedgerApiClient(mvc);
        cardholder = api.createAccount("Alice receivable", "ASSET", "USD");
        merchant = api.createAccount("Coffee Shop payable", "LIABILITY", "USD");
    }

    @Test
    void paginatesNewestFirst() throws Exception {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 1; i <= 5; i++) {
            postAt(base.plusSeconds(i * 60L), 100L * i);
        }

        mvc.perform(get("/accounts/{id}/transactions", cardholder).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].amountMinor").value(500))
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.content[1].amountMinor").value(400));

        mvc.perform(get("/accounts/{id}/transactions", cardholder).param("page", "2").param("size", "2"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].amountMinor").value(100));
    }

    @Test
    void showsOnlyThisAccountsSide() throws Exception {
        postAt(Instant.now(), 4250);

        mvc.perform(get("/accounts/{id}/transactions", merchant))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"));
    }

    @Test
    void unknownAccountReturns404() throws Exception {
        mvc.perform(get("/accounts/{id}/transactions", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void rejectsOversizedPages() throws Exception {
        mvc.perform(get("/accounts/{id}/transactions", cardholder).param("size", "1000"))
                .andExpect(status().isBadRequest());
    }

    private void postAt(Instant occurredAt, long amount) throws Exception {
        api.postTransaction("""
                {"description": "charge", "currency": "USD", "occurredAt": "%s", "entries": [
                  {"accountId": "%s", "direction": "DEBIT",  "amountMinor": %d},
                  {"accountId": "%s", "direction": "CREDIT", "amountMinor": %d}
                ]}""".formatted(occurredAt, cardholder, amount, merchant, amount))
                .andExpect(status().isCreated());
    }
}
