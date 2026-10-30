package com.quizopia.quiz.configuration;

import com.quizopia.quiz.application.DeletionSagaObserver;
import com.quizopia.quiz.domain.QuizDeletionState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Micrometer/SLF4J adapter behind {@link DeletionSagaObserver}. Registered only while
 * quizopia.deletion.enabled=true, so every meter and WARN stays inert on default-OFF
 * deployments. Emits {@code quizopia.quiz.deletion.transition}{to,reason} counters and one
 * {@code quizopia.quiz.deletion.nonterminal.operations}{status} gauge per fixed nonterminal
 * status, backed by a count snapshot refreshed once per reconcile round (no per-request
 * database hits). Failure reports are bounded WARN logs carrying operation id, durable stage
 * and failure class only - no titles, no exception messages, no PII.
 */
final class MicrometerDeletionSagaObserver implements DeletionSagaObserver {
    private static final Logger log = LoggerFactory.getLogger(MicrometerDeletionSagaObserver.class);

    static final String TRANSITION_METRIC = "quizopia.quiz.deletion.transition";
    static final String NONTERMINAL_METRIC = "quizopia.quiz.deletion.nonterminal.operations";

    private final MeterRegistry registry;
    private final AtomicReference<Map<QuizDeletionState, Long>> nonterminalByStatus = new AtomicReference<>(Map.of());

    MicrometerDeletionSagaObserver(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (QuizDeletionState status : QuizDeletionState.values()) {
            if (status.mainOrder() < QuizDeletionState.PRIMARY_PURGED.mainOrder()) {
                Gauge.builder(NONTERMINAL_METRIC, nonterminalByStatus, counts -> counts.get()
                                .getOrDefault(status, 0L))
                        .tag("status", status.name())
                        .register(registry);
            }
        }
    }

    @Override
    public void onTransition(QuizDeletionState target, String reason) {
        registry.counter(TRANSITION_METRIC, "to", target.name(), "reason", DeletionSagaObserver.reasonClass(reason))
                .increment();
    }

    @Override
    public void onStatusCounts(Map<QuizDeletionState, Long> counts) {
        nonterminalByStatus.set(Map.copyOf(counts));
    }

    @Override
    public void onReconcileFailure(UUID operationId, QuizDeletionState stage, String failureType) {
        log.warn("Deletion saga reconcile failed operationId={} stage={} failure={}", operationId, stage, failureType);
    }
}
