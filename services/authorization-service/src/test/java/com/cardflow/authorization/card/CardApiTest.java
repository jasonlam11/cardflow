package com.cardflow.authorization.card;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.cardflow.authorization.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CardApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void createsCardWithTokenAndLast4Only() throws Exception {
        String body = mvc.perform(post("/cards").contentType(MediaType.APPLICATION_JSON)
                .content("{\"creditLimitMinor\": 500000, \"currency\": \"USD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cardToken").value(matchesPattern("^tok_[0-9a-f]{24}$")))
                .andExpect(jsonPath("$.last4").value(matchesPattern("^[0-9]{4}$")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.availableCreditMinor").value(500000))
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(body, "$.id");
        mvc.perform(get("/cards/{id}", id)).andExpect(jsonPath("$.creditLimitMinor").value(500000));
    }

    @Test
    void freezesCard() throws Exception {
        String body = mvc.perform(post("/cards").contentType(MediaType.APPLICATION_JSON)
                .content("{\"creditLimitMinor\": 1000, \"currency\": \"USD\"}"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(put("/cards/{id}/status", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"FROZEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    @Test
    void rejectsInvalidCard() throws Exception {
        mvc.perform(post("/cards").contentType(MediaType.APPLICATION_JSON)
                .content("{\"creditLimitMinor\": -1, \"currency\": \"usd\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.creditLimitMinor").exists())
                .andExpect(jsonPath("$.errors.currency").exists());
    }

    @Test
    void unknownCardReturns404() throws Exception {
        mvc.perform(get("/cards/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
