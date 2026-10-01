package com.cardflow.ledger.common;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.cardflow.ledger.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorrelationIdTest {

    @Autowired
    MockMvc mvc;

    @Test
    void echoesValidIncomingId() throws Exception {
        mvc.perform(get("/accounts/{id}", UUID.randomUUID()).header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
    }

    @Test
    void replacesUnsafeId() throws Exception {
        mvc.perform(get("/accounts/{id}", UUID.randomUUID()).header("X-Correlation-Id", "bad id\r\n"))
                .andExpect(header().string("X-Correlation-Id", matchesPattern("^[0-9a-f-]{36}$")));
    }
}
