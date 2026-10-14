package com.quizopia.assessment.infrastructure;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.configuration.ClosureProperties;
import com.quizopia.assessment.domain.attempt.AttemptRecord.Reason;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hints are bounded; every mutation rechecks durable authority. Never writes CLOSED. */
@Component
@EnableScheduling
public class ClosureWorker {
    private final ClosureStore store;
    private final ClosureDependencies dependencies;
    private final AttemptFinalization finalizer;
    private final ClosureProperties properties;
    private final MeterRegistry metrics;
    private final Map<String, Double> gauges = new ConcurrentHashMap<>();

    public ClosureWorker(
            ClosureStore store,
            ClosureDependencies dependencies,
            AttemptFinalization finalizer,
            ClosureProperties properties,
            MeterRegistry metrics) {
        this.store = store;
        this.dependencies = dependencies;
        this.finalizer = finalizer;
        this.properties = properties;
        this.metrics = metrics;
        for (var name : java.util.List.of("unconfirmed", "oldest_seconds", "eligible")) {
            gauges.put(name, 0d);
            metrics.gauge("assessment.closure." + name, gauges, m -> m.get(name));
        }
    }

    private void count(String outcome) {
        metrics.counter("assessment.closure.work", "outcome", outcome).increment();
    }

    public void process(ClosureStore.Work work) {
        String outcome = "STALE";
        try {
            switch (work.stage()) {
                case "NOTICE_PENDING" -> {
                    try {
                        dependencies.requireInbox();
                    } catch (RuntimeException e) {
                        outcome = "INBOX_UNAVAILABLE";
                        break;
                    }
                    if (store.publish(work)) outcome = "PUBLISHED";
                }
                case "NOTICE_PUBLISHED" -> {
                    try {
                        dependencies.requireInbox();
                    } catch (RuntimeException e) {
                        outcome = "INBOX_UNAVAILABLE";
                        break;
                    }
                    store.published(work);
                    ClosureDependencies.Eligibility eligibility;
                    try {
                        eligibility = dependencies.eligible(work.recipientId());
                    } catch (RuntimeException e) {
                        outcome = "AUTHORITY_UNAVAILABLE";
                        break;
                    }
                    if (!work.recipientId().equals(eligibility.studentId()) || !eligibility.eligible()) {
                        outcome = "INELIGIBLE";
                        break;
                    }
                    if (store.confirm(work, eligibility)) outcome = "CONFIRMED";
                }
                case "NOTICE_CONFIRMED" -> {
                    if (finalizer
                                    .noticeFinalize(work.attemptId(), work.requestId())
                                    .kind()
                            == AttemptFinalization.Kind.SUBMITTED) outcome = "FINALIZED";
                }
                case "BOUNDED_TIMEOUT" -> {
                    if (finalizer
                                    .systemFinalize(work.attemptId(), Reason.BOUNDED_TIMEOUT)
                                    .kind()
                            == AttemptFinalization.Kind.SUBMITTED) outcome = "FINALIZED";
                }
                default -> {}
            }
        } catch (RuntimeException e) {
            outcome = "FAILED";
        }
        count(outcome.toLowerCase(java.util.Locale.ROOT));
        try {
            store.workOutcome(work, outcome);
        } catch (RuntimeException e) {
            count("audit_retry_required");
        }
    }

    @Scheduled(fixedDelayString = "${quizopia.assessment.closure.poll-millis:5000}")
    public void tick() {
        if (!properties.workerEnabled()) return;
        try {
            int units = 0;
            for (var work : store.candidates(properties.scanLimit())) {
                if (units++ >= properties.unitBudget()) break;
                process(work);
            }
            gauges.putAll(store.backlog());
        } catch (RuntimeException e) {
            count("scan_failed");
        }
    }
}
