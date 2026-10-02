package com.cardflow.authorization;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/** Test helper so tests read as "create card, charge it" instead of JSON plumbing. */
public final class AuthApiClient {

    private final MockMvc mvc;

    public AuthApiClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    public String createCard(long limitMinor) throws Exception {
        String body = mvc.perform(post("/cards").contentType(MediaType.APPLICATION_JSON)
                .content("{\"creditLimitMinor\": %d, \"currency\": \"USD\"}".formatted(limitMinor)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    public ResultActions charge(String key, String cardId, long amountMinor) throws Exception {
        return charge(key, cardId, amountMinor, "Blue Bottle Coffee");
    }

    public ResultActions charge(String key, String cardId, long amountMinor, String merchant) throws Exception {
        var request = post("/authorizations").contentType(MediaType.APPLICATION_JSON).content("""
                {"cardId": "%s", "merchantId": "m-123", "merchantName": "%s", "mcc": "5814",
                 "amountMinor": %d, "currency": "USD"}""".formatted(cardId, merchant, amountMinor));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }
}
