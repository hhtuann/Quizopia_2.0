package com.quizopia.quiz.application;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FB-015 PR C outbound port for the deletion coordinator. The application layer drives the
 * saga purely through this boundary; the infrastructure adapter carries the OAuth2
 * client-credentials transport to Assessment and Proctoring.
 */
public interface DeletionDependencies {
    /** Transport-level dependency failure; never carries quiz content. */
    class DependencyUnavailable extends RuntimeException {
        public DependencyUnavailable() {
            super("Deletion dependency is unavailable");
        }
    }

    record Operation(UUID operationId, UUID quizId) {}

    record RevocationStatus(Instant revokedAt, Instant acknowledgedAt) {}

    record PurgeOutcome(UUID operationId, UUID quizId, String status) {}

    record PurgeStatus(Instant acknowledgedAt, boolean purged, boolean retentionHoldActive) {}

    /**
     * Assessment-owned dependents inventory (publication and attempt ids) for one quiz;
     * a null instance means the inventory could not be fetched (dependency outage).
     */
    record Dependents(List<UUID> publicationIds, List<UUID> attemptIds) {
        public Dependents {
            publicationIds = List.copyOf(publicationIds == null ? List.of() : publicationIds);
            attemptIds = List.copyOf(attemptIds == null ? List.of() : attemptIds);
        }
    }

    URI assessmentBaseUrl();

    URI proctoringBaseUrl();

    /** Idempotent fence installation; any 200 answer (accepted or already-revoked) is success. */
    void revokeBatch(URI base, List<Operation> operations);

    /** Returns null when the fence is not installed yet (404); never guesses. */
    RevocationStatus revocationStatus(URI base, UUID quizId);

    List<PurgeOutcome> purgeBatch(URI base, List<Operation> operations);

    /**
     * Purge with the Assessment-owned dependents inventory so Proctoring can resolve
     * legacy (pre-V4, quiz_id NULL) sessions without any cross-service database join.
     */
    List<PurgeOutcome> purgeBatch(URI base, List<Operation> operations, Dependents dependents);

    /**
     * FB-015 correction H5: complete publication/attempt id inventory for the quiz
     * from Assessment-owned tables. Returns null when the fence is not installed yet
     * (404) or the dependency is unavailable.
     */
    Dependents assessmentDependents(URI base, UUID quizId);

    /** Returns null when no fence/purge state exists (404). */
    PurgeStatus purgeStatus(URI base, UUID quizId);
}
