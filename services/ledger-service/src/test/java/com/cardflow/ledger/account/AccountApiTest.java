package com.cardflow.ledger.account;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

import com.cardflow.ledger.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void createsAndFetchesAnAccount() throws Exception {
        String body = mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
                {"name": "Alice receivable", "type": "ASSET", "currency": "USD"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/accounts/")))
                .andExpect(jsonPath("$.name").value("Alice receivable"))
                .andExpect(jsonPath("$.type").value("ASSET"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(body, "$.id");
        mvc.perform(get("/accounts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void unknownAccountReturns404Problem() throws Exception {
        mvc.perform(get("/accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not found"));
    }

    @Test
    void invalidFieldsReturn400WithFieldErrors() throws Exception {
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
                {"name": "", "type": "ASSET", "currency": "usd"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.currency").value(containsString("ISO 4217")));
    }

    @Test
    void unknownEnumValueReturns400WithoutLeakingInternals() throws Exception {
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
                {"name": "x", "type": "SAVINGS", "currency": "USD"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("com.cardflow"))));
    }
}
