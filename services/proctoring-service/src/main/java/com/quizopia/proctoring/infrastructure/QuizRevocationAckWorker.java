package com.quizopia.proctoring.infrastructure;

import com.quizopia.proctoring.application.QuizRevocationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded drainer that acknowledges revocation fences once every in-flight capture or
 * monitoring access on affected sessions has committed and every media grant is fenced.
 * Independent single-thread scheduler; each round re-verifies durable authority under
 * session row locks, so an unacknowledged fence simply retries. No quiz content, evidence
 * or PII are retained or logged.
 */
public final class QuizRevocationAckWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(QuizRevocationAckWorker.class);
    private final QuizRevocationService service;
    private final MeterRegistry metrics;
    private final int delaySeconds;
    private final int batchSize;
    private ScheduledExecutorService executor;
    private volatile boolean closed;

    public QuizRevocationAckWorker(
            QuizRevocationService service, MeterRegistry metrics, int delaySeconds, int batchSize) {
        this.service = service;
        this.metrics = metrics;
        this.delaySeconds = delaySeconds;
        this.batchSize = batchSize;
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("Quiz revocation acknowledger is closed");
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "proctoring-quiz-revocation-ack");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, delaySeconds, delaySeconds, TimeUnit.SECONDS);
    }

    void tick() {
        if (closed || Thread.currentThread().isInterrupted()) return;
        try {
            service.acknowledgeDue(batchSize);
        } catch (RuntimeException failure) {
            metrics.counter("quizopia.proctoring.revocation.ack", "outcome", "unavailable")
                    .increment();
            log.debug("Quiz revocation acknowledgment round unavailable");
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
