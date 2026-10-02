package com.cardflow.authorization.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cardflow.authorization.TestcontainersConfiguration;
import com.cardflow.authorization.authorization.AuthorizationDtos.AuthorizationRequest;
import com.cardflow.authorization.card.CardService;

/**
 * Race conditions only show up under real concurrency, so these tests fire
 * many requests at once (released together by a latch) against real Postgres.
 */
@SpringBootTest(properties = "cardflow.outbox.enabled=false")
@Import(TestcontainersConfiguration.class)
class AuthorizationConcurrencyTest {

    @Autowired
    AuthorizationService service;

    @Autowired
    CardService cards;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void parallelChargesNeverExceedCreditLimit() throws Exception {
        UUID card = cards.create(10_000, "USD").getId();
        var request = new AuthorizationRequest(card, "m1", "Coffee", "5814", 1_000, "USD");

        // 20 different charges of $10 on a $100 card: exactly 10 may succeed
        List<AuthorizationStatus> results = runConcurrently(20,
                i -> () -> service.authorize("parallel-" + card + "-" + i, request).authorization().getStatus());

        assertThat(results).filteredOn(s -> s == AuthorizationStatus.APPROVED).hasSize(10);
        assertThat(results).filteredOn(s -> s == AuthorizationStatus.DECLINED).hasSize(10);
        assertThat(jdbc.queryForObject("""
                SELECT SUM(amount_minor) FROM authorizations WHERE card_account_id = ? AND status = 'APPROVED'""",
                Long.class, card)).isEqualTo(10_000);
    }

    @Test
    void parallelDuplicatesOfOneRequestCreateOneAuthorization() throws Exception {
        UUID card = cards.create(10_000, "USD").getId();
        var request = new AuthorizationRequest(card, "m1", "Coffee", "5814", 1_000, "USD");
        String key = "dup-" + card;

        // The same request sent 10 times at once (e.g. a client retrying aggressively)
        List<UUID> ids = runConcurrently(10, i -> () -> service.authorize(key, request).authorization().getId());

        assertThat(ids).hasSize(10).containsOnly(ids.getFirst());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authorizations WHERE idempotency_key = ?",
                Long.class, key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_events WHERE payload->'payload'->>'authorizationId' = ?""",
                Long.class, ids.getFirst().toString())).isEqualTo(1);
    }

    interface TaskFactory<T> {
        Callable<T> create(int index);
    }

    private static <T> List<T> runConcurrently(int n, TaskFactory<T> factory) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<T> task = factory.create(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
