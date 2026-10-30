package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizDeletionOperation;
import com.quizopia.quiz.domain.QuizDeletionState;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * FB-015 PR C deletion saga (ADR-018 forward-only coordinator). Drives each durable operation
 * through dependency fencing, revocation acknowledgment, dependent purge and finally the
 * quiz-owned hard purge, leaving the minimal operation ledger as the tombstone. Idempotent on
 * operationId: every dependency call is redelivery-safe and every state change is
 * forward-only. A dependency outage parks the operation in WAITING_DEPENDENCY and retries on
 * the next bounded round; an assessment retention hold surfaces as BLOCKED_BY_RETENTION until
 * the dependent purge reports PURGED. ALL_COPIES_ERASED is deliberately unreachable in this
 * PR: backup and media-provider erasure evidence does not exist yet, so a PRIMARY_PURGED
 * operation honestly stays there with a copies-pending note on the public status.
 */
public class DeletionSagaService {
    /** Purged primary rows are never reported as fully erased copies; see class javadoc. */
    public static final String COPIES_PENDING_NOTE = "COPY_EXPIRY_PENDING";

    private final QuizDeletionOperationRepository deletionOperationRepository;
    private final DeletionOperationService deletionOperationService;
    private final DeletionDependencies dependencies;
    private final DeletionSagaObserver observer;
    private final int batchLimit;

    public DeletionSagaService(
            QuizDeletionOperationRepository deletionOperationRepository,
            DeletionOperationService deletionOperationService,
            DeletionDependencies dependencies,
            DeletionSagaObserver observer,
            int batchLimit) {
        this.deletionOperationRepository =
                Objects.requireNonNull(deletionOperationRepository, "deletionOperationRepository");
        this.deletionOperationService = Objects.requireNonNull(deletionOperationService, "deletionOperationService");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.batchLimit = batchLimit;
    }

    /** One bounded reconciliation round over every nonterminal operation. */
    public void reconcileDue() {
        try {
            observer.onStatusCounts(deletionOperationRepository.countNonterminalByStatus());
        } catch (RuntimeException failure) {
            // Best-effort gauge refresh only; the reconcile round below still runs.
        }
        List<QuizDeletionOperation> due;
        try {
            due = deletionOperationRepository.findSagaCandidates(batchLimit);
        } catch (RuntimeException unavailable) {
            return;
        }
        for (QuizDeletionOperation operation : due) {
            try {
                reconcile(operation);
            } catch (RuntimeException failure) {
                observer.onReconcileFailure(
                        operation.id(), operation.status(), failure.getClass().getName());
            }
        }
    }

    /** Outbox handoff for quiz.deletion.requested.v1: idempotent revocation dispatch. */
    public void onDeletionCommand(UUID operationId) {
        QuizDeletionOperation operation = null;
        try {
            operation = deletionOperationRepository.findById(operationId).orElse(null);
            if (operation == null) {
                return;
            }
            reconcile(operation);
        } catch (RuntimeException failure) {
            observer.onReconcileFailure(
                    operationId,
                    operation == null ? null : operation.status(),
                    failure.getClass().getName());
            throw new DeletionDependencies.DependencyUnavailable();
        }
    }

    private void reconcile(QuizDeletionOperation operation) {
        if (operation.mainProgress() < QuizDeletionState.ACCESS_REVOKED.mainOrder()) {
            fenceDependencies(operation);
        } else if (operation.mainProgress() < QuizDeletionState.PRIMARY_PURGED.mainOrder()) {
            purgeDependentsAndPrimary(operation);
        }
        // PRIMARY_PURGED (and everything beyond) needs backup/provider erasure evidence that
        // does not exist in this PR; the saga intentionally stops here.
    }

    private void fenceDependencies(QuizDeletionOperation operation) {
        UUID operationId = operation.id();
        UUID quizId = operation.quizId();
        var command = List.of(new DeletionDependencies.Operation(operationId, quizId));
        try {
            // Idempotent redelivery on every round until acknowledged.
            dependencies.revokeBatch(dependencies.assessmentBaseUrl(), command);
            dependencies.revokeBatch(dependencies.proctoringBaseUrl(), command);
        } catch (RuntimeException unavailable) {
            park(operation, QuizDeletionState.DEPENDENCIES_FENCING, "dependency-unavailable");
            return;
        }
        var assessment = dependencies.revocationStatus(dependencies.assessmentBaseUrl(), quizId);
        var proctoring = dependencies.revocationStatus(dependencies.proctoringBaseUrl(), quizId);
        if (assessment == null
                || assessment.acknowledgedAt() == null
                || proctoring == null
                || proctoring.acknowledgedAt() == null) {
            park(operation, QuizDeletionState.DEPENDENCIES_FENCING, "waiting-revocation-ack");
            return;
        }
        // ACCESS_REVOKED only after BOTH dependent owners durably acknowledged cessation.
        deletionOperationService.advanceState(operationId, QuizDeletionState.ACCESS_REVOKED, null);
    }

    private void purgeDependentsAndPrimary(QuizDeletionOperation operation) {
        UUID operationId = operation.id();
        UUID quizId = operation.quizId();
        if (operation.mainProgress() < QuizDeletionState.PURGE_PENDING.mainOrder()) {
            deletionOperationService.advanceState(operationId, QuizDeletionState.PURGE_PENDING, null);
        }
        if (operation.mainProgress() < QuizDeletionState.PURGING.mainOrder()
                || operation.status() != QuizDeletionState.PURGING) {
            deletionOperationService.advanceState(operationId, QuizDeletionState.PURGING, null);
        }
        var command = List.of(new DeletionDependencies.Operation(operationId, quizId));
        try {
            // Both custodians must provide an affirmative, current hold classification
            // before either owner destroys content. Missing/malformed status fails closed.
            var assessment = dependencies.purgeStatus(dependencies.assessmentBaseUrl(), quizId);
            var proctoring = dependencies.purgeStatus(dependencies.proctoringBaseUrl(), quizId);
            if (assessment == null
                    || proctoring == null
                    || assessment.acknowledgedAt() == null
                    || proctoring.acknowledgedAt() == null) {
                park(operation, QuizDeletionState.PURGING, "waiting-revocation-ack");
                return;
            }
            if (assessment.retentionHoldActive() || proctoring.retentionHoldActive()) {
                park(operation, QuizDeletionState.PURGING, "blocked-by-retention");
                return;
            }
            if (!proctoring.purged()) {
                // Assessment remains untouched until Proctoring finishes, preserving the
                // immutable live inventory across bounded Proctoring rounds. After that,
                // never replay a now-shrunken Assessment inventory to Proctoring.
                var dependents = dependencies.assessmentDependents(dependencies.assessmentBaseUrl(), quizId);
                if (dependents == null) {
                    park(operation, QuizDeletionState.PURGING, "waiting-dependency-inventory");
                    return;
                }
                if (!acceptOutcome(
                        operation, dependencies.purgeBatch(dependencies.proctoringBaseUrl(), command, dependents)))
                    return;
                proctoring = dependencies.purgeStatus(dependencies.proctoringBaseUrl(), quizId);
                if (proctoring == null
                        || !proctoring.purged()
                        || proctoring.acknowledgedAt() == null
                        || proctoring.retentionHoldActive()) {
                    park(operation, QuizDeletionState.PURGING, "waiting-dependency-purge");
                    return;
                }
            }
            if (!acceptOutcome(operation, dependencies.purgeBatch(dependencies.assessmentBaseUrl(), command))) return;
            assessment = dependencies.purgeStatus(dependencies.assessmentBaseUrl(), quizId);
            proctoring = dependencies.purgeStatus(dependencies.proctoringBaseUrl(), quizId);
            if (assessment == null
                    || !assessment.purged()
                    || assessment.retentionHoldActive()
                    || assessment.acknowledgedAt() == null
                    || proctoring == null
                    || !proctoring.purged()
                    || proctoring.retentionHoldActive()
                    || proctoring.acknowledgedAt() == null) {
                park(operation, QuizDeletionState.PURGING, "waiting-dependency-purge");
                return;
            }
        } catch (RuntimeException unavailable) {
            park(operation, QuizDeletionState.PURGING, "dependency-unavailable");
            return;
        }
        primaryPurge(operation);
    }

    private boolean acceptOutcome(QuizDeletionOperation operation, List<DeletionDependencies.PurgeOutcome> outcomes) {
        if (outcomes == null
                || outcomes.size() != 1
                || outcomes.getFirst() == null
                || !operation.id().equals(outcomes.getFirst().operationId())
                || !operation.quizId().equals(outcomes.getFirst().quizId())) {
            park(operation, QuizDeletionState.PURGING, "invalid-purge-receipt");
            return false;
        }
        String status = outcomes.getFirst().status();
        if ("PURGED".equals(status)) return true;
        String reason =
                switch (status == null ? "" : status) {
                    case "BLOCKED_BY_RETENTION" -> "blocked-by-retention";
                    case "IN_PROGRESS" -> "purge-in-progress";
                    case "RECONCILIATION_BLOCKED" -> "legacy_dependents_unresolved";
                    case "PENDING_PROVIDER" -> "pending-provider";
                    case "INVENTORY_MISMATCH" -> "inventory-mismatch";
                    case "WAITING_REVOCATION_ACK" -> "waiting-revocation-ack";
                    default -> "invalid-purge-receipt";
                };
        park(operation, QuizDeletionState.PURGING, reason);
        return false;
    }

    private void primaryPurge(QuizDeletionOperation operation) {
        // Hard-delete quiz-owned content in dependency-safe order (versions, drafts, quiz);
        // the minimal operation ledger stays as the anti-resurrection tombstone. The scrub
        // and the PRIMARY_PURGED advance share the row-deletion transaction (H4) inside
        // DeletionOperationService.completePrimaryPurge.
        deletionOperationService.completePrimaryPurge(operation.id(), operation.quizId());
    }
    /**
     * Moves the operation to the given nonterminal state when it is not already there. The
     * target main state is only recorded when the durable main progress has not reached it
     * yet (reloading fresh state, because the caller may hold an older snapshot); otherwise
     * only the parking state is recorded.
     */
    @Transactional
    void park(QuizDeletionOperation stale, QuizDeletionState mainTarget, String reason) {
        try {
            var operation = deletionOperationRepository.findById(stale.id()).orElse(null);
            if (operation == null) return;
            if (operation.status().isMain() && operation.mainProgress() < mainTarget.mainOrder()) {
                operation = deletionOperationService.advanceState(operation.id(), mainTarget, null);
            }
            QuizDeletionState parking = "blocked-by-retention".equals(reason)
                    ? QuizDeletionState.BLOCKED_BY_RETENTION
                    : QuizDeletionState.WAITING_DEPENDENCY;
            if (operation.mainProgress() >= QuizDeletionState.PRIMARY_PURGED.mainOrder()) return;
            if (!operation.status().equals(parking) || !Objects.equals(operation.lastReason(), reason)) {
                deletionOperationService.advanceState(operation.id(), parking, reason);
            }
        } catch (IllegalDeletionTransitionException ignored) {
            // Another worker advanced the same operation concurrently.
        }
    }
}
