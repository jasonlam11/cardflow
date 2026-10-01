package com.cardflow.ledger.transaction;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TransactionApiTest {

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
    void postsBalancedTransactionAndFetchesIt() throws Exception {
        String body = api.postTransfer(cardholder, merchant, 4250)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/transactions/")))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].amountMinor").value(4250))
                .andExpect(jsonPath("$.entries[0].currency").value("USD"))
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(body, "$.id");
        mvc.perform(get("/transactions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("test charge"))
                .andExpect(jsonPath("$.entries.length()").value(2));
    }

    @Test
    void unbalancedTransactionReturns400() throws Exception {
        api.postTransaction("""
                {"description": "bad", "currency": "USD", "entries": [
                  {"accountId": "%s", "direction": "DEBIT",  "amountMinor": 4250},
                  {"accountId": "%s", "direction": "CREDIT", "amountMinor": 4000}
                ]}""".formatted(cardholder, merchant))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid posting"))
                .andExpect(jsonPath("$.detail").value("Transaction is unbalanced: debits 4250 != credits 4000"));
    }

    @Test
    void unknownAccountReturns400() throws Exception {
        api.postTransfer(cardholder, UUID.randomUUID().toString(), 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("does not exist")));
    }

    @Test
    void currencyMismatchReturns400() throws Exception {
        String euroMerchant = api.createAccount("Paris Cafe payable", "LIABILITY", "EUR");
        api.postTransfer(cardholder, euroMerchant, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("is in EUR")));
    }

    @Test
    void singleEntryFailsValidation() throws Exception {
        api.postTransaction("""
                {"description": "one-legged", "currency": "USD", "entries": [
                  {"accountId": "%s", "direction": "DEBIT", "amountMinor": 100}
                ]}""".formatted(cardholder))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.entries").value(containsString("between 2 and 100")));
    }

    @Test
    void negativeAmountFailsValidation() throws Exception {
        api.postTransfer(cardholder, merchant, -500)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void unknownTransactionReturns404() throws Exception {
        mvc.perform(get("/transactions/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
