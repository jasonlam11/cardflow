package com.cardflow.ledger.transaction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.cardflow.ledger.LedgerApiClient;
import com.cardflow.ledger.TestcontainersConfiguration;

/** End to end: create accounts, post transactions, read derived balances. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BalanceApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void balancesReflectPostedTransactions() throws Exception {
        var api = new LedgerApiClient(mvc);
        String alice = api.createAccount("Alice receivable", "ASSET", "USD");
        String coffeeShop = api.createAccount("Coffee Shop payable", "LIABILITY", "USD");
        String cash = api.createAccount("Settlement cash", "ASSET", "USD");

        api.postTransfer(alice, coffeeShop, 4250).andExpect(status().isCreated()); // Alice buys coffee
        api.postTransfer(cash, alice, 1000).andExpect(status().isCreated());       // Alice pays $10 back

        mvc.perform(get("/accounts/{id}/balance", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(3250))
                .andExpect(jsonPath("$.totalDebitsMinor").value(4250))
                .andExpect(jsonPath("$.totalCreditsMinor").value(1000));
        mvc.perform(get("/accounts/{id}/balance", coffeeShop))
                .andExpect(jsonPath("$.balanceMinor").value(4250));
        mvc.perform(get("/accounts/{id}/balance", cash))
                .andExpect(jsonPath("$.balanceMinor").value(1000));
    }

    @Test
    void newAccountHasZeroBalance() throws Exception {
        String id = new LedgerApiClient(mvc).createAccount("Empty", "REVENUE", "USD");
        mvc.perform(get("/accounts/{id}/balance", id))
                .andExpect(jsonPath("$.balanceMinor").value(0));
    }

    @Test
    void unknownAccountReturns404() throws Exception {
        mvc.perform(get("/accounts/{id}/balance", UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
