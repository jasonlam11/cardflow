package com.cardflow.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/** Small helper so API tests read as "create account, post transaction" instead of JSON plumbing. */
public final class LedgerApiClient {

    private final MockMvc mvc;

    public LedgerApiClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    public String createAccount(String name, String type, String currency) throws Exception {
        String body = mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
                {"name": "%s", "type": "%s", "currency": "%s"}""".formatted(name, type, currency)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** Posts a two-entry transaction: debit one account, credit another. */
    public ResultActions postTransfer(String debitAccount, String creditAccount, long amountMinor) throws Exception {
        return postTransaction("""
                {"description": "test charge", "currency": "USD", "entries": [
                  {"accountId": "%s", "direction": "DEBIT",  "amountMinor": %d},
                  {"accountId": "%s", "direction": "CREDIT", "amountMinor": %d}
                ]}""".formatted(debitAccount, amountMinor, creditAccount, amountMinor));
    }

    public ResultActions postTransaction(String json) throws Exception {
        return mvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
