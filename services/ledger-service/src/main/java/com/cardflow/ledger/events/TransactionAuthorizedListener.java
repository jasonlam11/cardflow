package com.cardflow.ledger.events;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.cardflow.ledger.common.CorrelationIdFilter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka entry point. Offsets are committed only after this method returns,
 * i.e. after the DB transaction commits. A crash in between means redelivery,
 * which {@link AuthorizedTransactionPoster} absorbs by deduplicating.
 */
@Component
public class TransactionAuthorizedListener {

    private final JsonMapper json;
    private final AuthorizedTransactionPoster poster;

    public TransactionAuthorizedListener(JsonMapper json, AuthorizedTransactionPoster poster) {
        this.json = json;
        this.poster = poster;
    }

    @KafkaListener(topics = "${cardflow.events.topic}")
    public void onMessage(ConsumerRecord<String, String> record) {
        Header correlation = record.headers().lastHeader("correlationId");
        if (correlation != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, new String(correlation.value(), StandardCharsets.UTF_8));
        }
        try {
            poster.apply(parse(record.value()));
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private TransactionAuthorizedEvent parse(String value) {
        TransactionAuthorizedEvent event;
        try {
            event = json.readValue(value, TransactionAuthorizedEvent.class);
        } catch (JacksonException e) {
            throw new UnprocessableEventException("Malformed event JSON", e);
        }
        if (event.eventId() == null || event.payload() == null || !TransactionAuthorizedEvent.TYPE.equals(event.eventType())
                || event.schemaVersion() != 1) {
            throw new UnprocessableEventException("Unsupported event: type=" + event.eventType() + " version="
                    + event.schemaVersion(), null);
        }
        return event;
    }
}
