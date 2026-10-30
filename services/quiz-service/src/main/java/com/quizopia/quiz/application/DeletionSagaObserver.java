package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizDeletionState;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FB-015 observability seam for the deletion saga. The application layer must stay free of
 * logging and metrics dependencies (ArchitectureTest#applicationUsesPorts), so durable state
 * transitions, per-round nonterminal status counts and bounded reconcile failures are reported
 * through this port; the configuration layer wires a Micrometer/SLF4J adapter while deletion is
 * enabled and the {@link #NOOP} default otherwise, keeping default-OFF deployments fully inert.
 * Signals carry only durable states, the bounded reason vocabulary below and failure class
 * names - never quiz titles, free-form operator text or other PII.
 */
public interface DeletionSagaObserver {
    /** Park reasons actually produced by the saga; anything else collapses to {@code other}. */
    Set<String> KNOWN_REASONS = Set.of(
            "dependency-unavailable",
            "waiting-revocation-ack",
            "blocked-by-retention",
            "waiting-dependency-inventory",
            "waiting-dependency-purge",
            "invalid-purge-receipt",
            "inventory-mismatch",
            "purge-in-progress",
            "pending-provider",
            "legacy_dependents_unresolved");

    /** Inert default so disabled deployments and plain unit tests never touch a registry. */
    DeletionSagaObserver NOOP = new DeletionSagaObserver() {
        @Override
        public void onTransition(QuizDeletionState target, String reason) {}

        @Override
        public void onStatusCounts(Map<QuizDeletionState, Long> nonterminalByStatus) {}

        @Override
        public void onReconcileFailure(UUID operationId, QuizDeletionState stage, String failureType) {}
    };

    /** Recorded after every durable forward or park transition written through advanceState. */
    void onTransition(QuizDeletionState target, String reason);

    /** Fresh nonterminal operation counts per current status, refreshed once per reconcile round. */
    void onStatusCounts(Map<QuizDeletionState, Long> nonterminalByStatus);

    /** Bounded failure signal for one reconcile round or outbox command dispatch. */
    void onReconcileFailure(UUID operationId, QuizDeletionState stage, String failureType);

    /**
     * Collapses a raw park reason onto the fixed {@link #KNOWN_REASONS} vocabulary so metric tag
     * cardinality can never grow with reason text; unknown values become {@code other} and an
     * absent reason {@code none}.
     */
    static String reasonClass(String reason) {
        if (reason == null) {
            return "none";
        }
        return KNOWN_REASONS.contains(reason) ? reason : "other";
    }
}
