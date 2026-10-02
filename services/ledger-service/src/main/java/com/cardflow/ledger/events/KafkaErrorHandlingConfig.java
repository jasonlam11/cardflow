package com.cardflow.ledger.events;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.cardflow.ledger.transaction.InvalidPostingException;

/**
 * What happens when an event fails:
 * - temporary problems (e.g. DB briefly down): retry 3 times, 1 s apart
 * - events that can never succeed: no retries
 * Either way, once given up on, the event goes to "&lt;topic&gt;.DLT" with the
 * error in its headers, so one bad message never blocks the partition.
 */
@Configuration
class KafkaErrorHandlingConfig {

    static final String DLT_SUFFIX = ".DLT";

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3));
        handler.addNotRetryableExceptions(UnprocessableEventException.class, InvalidPostingException.class);
        // Make every failed attempt visible in the logs, not just the final give-up
        handler.setLogLevel(KafkaException.Level.WARN);
        return handler;
    }
}
