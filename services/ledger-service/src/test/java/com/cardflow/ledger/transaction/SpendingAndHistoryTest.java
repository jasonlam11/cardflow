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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.cardflow.ledger.TestcontainersConfiguration;
import com.cardflow.ledger.events.AuthorizedTransactionPoster;
import com.cardflow.ledger.events.TransactionAuthorizedEvent;
import com.cardflow.ledger.events.TransactionAuthorizedEvent.Payload;

/** Category spending and filtered history, fed through the same path as Kafka events. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SpendingAndHistoryTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthorizedTransactionPoster poster;

    @Autowired
    JdbcTemplate jdbc;

    UUID card;
    String account;

    @BeforeEach
    void setUp() {
        card = UUID.randomUUID();
        charge("2026-01-03T09:00:00Z", "5814", "Blue Bottle Coffee", 500);
        charge("2026-01-10T12:00:00Z", "5814", "Blue Bottle Coffee", 700);
        charge("2026-01-20T18:00:00Z", "5411", "Fresh Market Grocery", 4_000);
        charge("2026-02-02T10:00:00Z", "5411", "Fresh Market Grocery", 1_000);
        account = jdbc.queryForObject("SELECT id::text FROM accounts WHERE external_ref = ?", String.class, "card:" + card);
    }

    @Test
    void spendingByCategoryForADateRange() throws Exception {
        mvc.perform(get("/accounts/{id}/spending", account).param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].mcc").value("5411"))
                .andExpect(jsonPath("$[0].totalMinor").value(4_000))
                .andExpect(jsonPath("$[1].mcc").value("5814"))
                .andExpect(jsonPath("$[1].totalMinor").value(1_200))
                .andExpect(jsonPath("$[1].count").value(2));
    }

    @Test
    void toDateIsInclusive() throws Exception {
        mvc.perform(get("/accounts/{id}/spending", account).param("from", "2026-02-02").param("to", "2026-02-02"))
                .andExpect(jsonPath("$[0].totalMinor").value(1_000));
    }

    @Test
    void historyCanBeSortedByAmountAndFilteredByDate() throws Exception {
        mvc.perform(get("/accounts/{id}/transactions", account).param("sort", "AMOUNT").param("size", "2"))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].amountMinor").value(4_000))
                .andExpect(jsonPath("$.content[0].merchantName").value("Fresh Market Grocery"))
                .andExpect(jsonPath("$.content[0].mcc").value("5411"))
                .andExpect(jsonPath("$.content[1].amountMinor").value(1_000));

        mvc.perform(get("/accounts/{id}/transactions", account).param("from", "2026-01-05").param("to", "2026-01-31"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].amountMinor").value(4_000)); // newest first
    }

    @Test
    void invalidDatesAndSortAreRejected() throws Exception {
        mvc.perform(get("/accounts/{id}/spending", account).param("from", "last-month")).andExpect(status().isBadRequest());
        mvc.perform(get("/accounts/{id}/transactions", account).param("sort", "RANDOM")).andExpect(status().isBadRequest());
    }

    private void charge(String at, String mcc, String merchant, long amount) {
        poster.apply(new TransactionAuthorizedEvent(UUID.randomUUID(), TransactionAuthorizedEvent.TYPE, 1,
                Instant.parse(at), "test", new Payload(UUID.randomUUID(), card, "4242", "m-" + mcc, merchant, mcc,
                        amount, "USD")));
    }
}
