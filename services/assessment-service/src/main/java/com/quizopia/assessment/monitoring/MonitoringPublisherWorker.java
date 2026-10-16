package com.quizopia.assessment.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Recovery is independent of the closure scheduler; no actor/token or answer is retained. */
public final class MonitoringPublisherWorker implements AutoCloseable {
    private final MonitoringOutbox store;
    private final AssessmentMonitoringPublisher publisher;
    private final MeterRegistry metrics;
    private final UUID owner = UUID.randomUUID();
    private ScheduledExecutorService executor;
    private volatile boolean closed;

    public MonitoringPublisherWorker(
            MonitoringOutbox store, AssessmentMonitoringPublisher publisher, MeterRegistry metrics) {
        this.store = store;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("Monitoring publisher is closed");
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "assessment-monitoring-publisher");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    public void tick() {
        if (closed || Thread.currentThread().isInterrupted()) return;
        try {
            for (var event : store.claim(owner, 5)) {
                if (closed || Thread.currentThread().isInterrupted()) {
                    try {
                        store.retry(event);
                    } catch (RuntimeException unavailable) {
                        /* The durable lease will expire. */
                    }
                    continue;
                }
                try {
                    publisher.publish(event.payload(), event.id());
                    store.published(event);
                    metrics.counter("quizopia.assessment.monitoring.publish", "outcome", "confirmed")
                            .increment();
                } catch (RuntimeException failure) {
                    metrics.counter("quizopia.assessment.monitoring.publish", "outcome", "retry")
                            .increment();
                    try {
                        store.retry(event);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        } catch (RuntimeException failure) {
            metrics.counter("quizopia.assessment.monitoring.publish", "outcome", "store_unavailable")
                    .increment();
        }
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
