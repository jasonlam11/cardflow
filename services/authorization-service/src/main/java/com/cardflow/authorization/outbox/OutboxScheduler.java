package com.cardflow.authorization.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the relay on a fixed delay. Disabled in tests that drive the relay by hand. */
@Component
@ConditionalOnProperty(name = "cardflow.outbox.enabled", havingValue = "true", matchIfMissing = true)
class OutboxScheduler {

    private final OutboxRelay relay;

    OutboxScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    // fixedDelay: the next run starts only after this one finishes, so runs never overlap
    @Scheduled(fixedDelayString = "${cardflow.outbox.poll-interval-ms}")
    void run() {
        relay.publishBatch();
    }
}
