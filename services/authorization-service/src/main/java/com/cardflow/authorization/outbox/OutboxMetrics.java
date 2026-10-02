package com.cardflow.authorization.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * How far behind event publishing is. Read from the outbox table at scrape
 * time, so it's correct across restarts and relay instances. A growing backlog
 * or age means Kafka or the relay is in trouble while authorizations continue.
 */
@Component
class OutboxMetrics {

    OutboxMetrics(JdbcTemplate jdbc, MeterRegistry metrics) {
        Gauge.builder("cardflow.outbox.backlog", jdbc,
                        j -> value(j, "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL"))
                .description("Events written but not yet published to Kafka").register(metrics);
        Gauge.builder("cardflow.outbox.oldest.age.seconds", jdbc, j -> value(j, """
                        SELECT COALESCE(EXTRACT(EPOCH FROM now() - MIN(created_at)), 0)
                          FROM outbox_events WHERE published_at IS NULL"""))
                .description("Age of the oldest unpublished event").register(metrics);
    }

    private static double value(JdbcTemplate jdbc, String sql) {
        try {
            Number n = jdbc.queryForObject(sql, Number.class);
            return n == null ? 0 : n.doubleValue();
        } catch (Exception e) {
            return Double.NaN; // DB unreachable: report "unknown", never fail the scrape
        }
    }
}
