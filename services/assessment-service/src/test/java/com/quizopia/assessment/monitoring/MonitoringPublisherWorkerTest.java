package com.quizopia.assessment.monitoring;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonitoringPublisherWorkerTest {
    @Test
    void shutdownDuringPublishReleasesRemainingClaimsWithoutMoreBrokerCalls() {
        var store = mock(MonitoringOutbox.class);
        var publisher = mock(AssessmentMonitoringPublisher.class);
        var worker = new MonitoringPublisherWorker(store, publisher, new SimpleMeterRegistry());
        var events = java.util.stream.IntStream.range(0, 3)
                .mapToObj(i -> new MonitoringOutbox.Event(UUID.randomUUID(), UUID.randomUUID(), new byte[] {1}))
                .toList();
        when(store.claim(any(), eq(5))).thenReturn(events);
        doAnswer(call -> {
                    worker.close();
                    return null;
                })
                .when(publisher)
                .publish(any(), any());
        worker.tick();
        verify(publisher, times(1)).publish(any(), any());
        verify(store).published(events.getFirst());
        verify(store).retry(events.get(1));
        verify(store).retry(events.get(2));
        assertThrows(IllegalStateException.class, worker::start);
    }

    @Test
    void closedWorkerCannotClaimNewDeliveryLeases() {
        var store = mock(MonitoringOutbox.class);
        var publisher = mock(AssessmentMonitoringPublisher.class);
        var worker = new MonitoringPublisherWorker(store, publisher, new SimpleMeterRegistry());
        worker.close();
        worker.tick();
        verifyNoInteractions(store, publisher);
    }
}
