package com.quizopia.assessment.realtime;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bounded claimed dispatch with exponential backoff and poison-event quarantine. Recovery is
 * independent of other schedulers; only allowlisted invalidation metadata is retained.
 */
public final class RealtimeInvalidationPublisherWorker implements AutoCloseable {
    private static final String METRIC = "quizopia.assessment.realtime-invalidation.publish";
    private final RealtimeInvalidationOutbox store;
    private final RealtimeInvalidationPublisher publisher;
    private final RealtimeInvalidationProperties properties;
    private final MeterRegistry metrics;
    private final UUID owner = UUID.randomUUID();
    private ScheduledExecutorService executor;
    private volatile boolean closed;

    public RealtimeInvalidationPublisherWorker(
            RealtimeInvalidationOutbox store,
            RealtimeInvalidationPublisher publisher,
            RealtimeInvalidationProperties properties,
            MeterRegistry metrics) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
        this.metrics = metrics;
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("Realtime invalidation publisher is closed");
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "assessment-realtime-invalidation-publisher");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::tick, properties.getPollDelaySeconds(), properties.getPollDelaySeconds(), TimeUnit.SECONDS);
    }

    public void tick() {
        if (closed || Thread.currentThread().isInterrupted()) return;
        try {
            for (var event : store.claim(owner, properties.getClaimBatch())) {
                if (closed || Thread.currentThread().isInterrupted()) {
                    release(event);
                    continue;
                }
                try {
                    publisher.publish(
                            event.id(),
                            event.eventType(),
                            event.resourceKind(),
                            event.resourceId(),
                            event.occurredAt());
                    store.published(event);
                    outcome("confirmed");
                } catch (RuntimeException failure) {
                    if (event.deliveryAttempts() >= properties.getMaxDeliveryAttempts()) {
                        try {
                            store.quarantined(event);
                            outcome("quarantined");
                        } catch (RuntimeException ignored) {
                            outcome("retry");
                        }
                    } else {
                        release(event);
                        outcome("retry");
                    }
                }
            }
        } catch (RuntimeException failure) {
            outcome("store_unavailable");
        }
    }

    private void release(RealtimeInvalidationOutbox.Event event) {
        // Exponential backoff capped at one minute; lease expiry bounds an uncertain release.
        int backoff = (int) Math.min(60, Math.pow(2, Math.max(0, event.deliveryAttempts() - 1)));
        try {
            store.retry(event, backoff);
        } catch (RuntimeException ignored) {
            /* The durable lease will expire. */
        }
    }

    private void outcome(String name) {
        metrics.counter(METRIC, "outcome", name).increment();
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
