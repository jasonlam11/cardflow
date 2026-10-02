package com.cardflow.authorization.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class OutboxSchedulerTest {

    final OutboxProperties props = new OutboxProperties("t", 500, 100, 1000);

    @Test
    void keepsDrainingWhileBatchesAreFull() {
        OutboxRelay relay = mock(OutboxRelay.class);
        when(relay.publishBatch()).thenReturn(100, 100, 100, 37);
        new OutboxScheduler(relay, props).run();
        verify(relay, times(4)).publishBatch();
    }

    @Test
    void stopsAfterOneBatchWhenNotFull() {
        OutboxRelay relay = mock(OutboxRelay.class);
        when(relay.publishBatch()).thenReturn(12);
        new OutboxScheduler(relay, props).run();
        verify(relay, times(1)).publishBatch();
    }

    @Test
    void boundsTheNumberOfBatchesPerRun() {
        OutboxRelay relay = mock(OutboxRelay.class);
        when(relay.publishBatch()).thenReturn(100);
        new OutboxScheduler(relay, props).run();
        verify(relay, times(OutboxScheduler.MAX_BATCHES_PER_RUN)).publishBatch();
        assertThat(OutboxScheduler.MAX_BATCHES_PER_RUN).isEqualTo(50);
    }
}
