package com.cardflow.authorization.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the relay on a fixed delay. When a batch comes back full there is probably more
 * waiting, so it keeps draining right away instead of sleeping until the next tick.
 * (Measured: polling one batch per tick capped publishing at batch-size / interval =
 * 200 events/s, and a 600 req/s load test built a 24k-event backlog.)
 * Disabled in tests that drive the relay by hand.
 */
@Component
@ConditionalOnProperty(name = "cardflow.outbox.enabled", havingValue = "true", matchIfMissing = true)
class OutboxScheduler {

    /** Upper bound on back-to-back batches per tick, so one run can't monopolize the scheduler thread. */
    static final int MAX_BATCHES_PER_RUN = 50;

    private final OutboxRelay relay;
    private final OutboxProperties props;

    OutboxScheduler(OutboxRelay relay, OutboxProperties props) {
        this.relay = relay;
        this.props = props;
    }

    // fixedDelay: the next run starts only after this one finishes, so runs never overlap
    @Scheduled(fixedDelayString = "${cardflow.outbox.poll-interval-ms}")
    void run() {
        int batches = 0;
        while (relay.publishBatch() >= props.batchSize() && ++batches < MAX_BATCHES_PER_RUN) {
            // full batch: drain the next one immediately
        }
    }
}
