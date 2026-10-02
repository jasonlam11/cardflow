package com.cardflow.authorization.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under cardflow.outbox.* in application.yml. */
@ConfigurationProperties("cardflow.outbox")
public record OutboxProperties(String topic, long pollIntervalMs, int batchSize, long sendTimeoutMs) {

    public OutboxProperties {
        if (sendTimeoutMs <= 0) {
            sendTimeoutMs = 10_000;
        }
    }
}
