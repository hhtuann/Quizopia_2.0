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
    private final QuizRepository quizRepository;
    private final DeletionOperationService deletionOperationService;
    private final DeletionDependencies dependencies;
    private final int batchLimit;

    public DeletionSagaService(
            QuizDeletionOperationRepository deletionOperationRepository,
            QuizRepository quizRepository,
            DeletionOperationService deletionOperationService,
            DeletionDependencies dependencies,
            int batchLimit) {
        this.deletionOperationRepository =
                Objects.requireNonNull(deletionOperationRepository, "deletionOperationRepository");
        this.quizRepository = Objects.requireNonNull(quizRepository, "quizRepository");
        this.deletionOperationService = Objects.requireNonNull(deletionOperationService, "deletionOperationService");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        this.batchLimit = batchLimit;
    }

    /** One bounded reconciliation round over every nonterminal operation. */
    public void reconcileDue() {
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
            }
        }
    }

    /** Outbox handoff for quiz.deletion.requested.v1: idempotent revocation dispatch. */
    public void onDeletionCommand(UUID operationId) {
        try {
            deletionOperationRepository.findById(operationId).ifPresent(this::reconcile);
        } catch (RuntimeException failure) {
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
        if (operation.mainProgress() == QuizDeletionState.ACCESS_REVOKED.mainOrder()) {
            deletionOperationService.advanceState(operationId, QuizDeletionState.PURGE_PENDING, null);
            deletionOperationService.advanceState(operationId, QuizDeletionState.PURGING, null);
        }
        var command = List.of(new DeletionDependencies.Operation(operationId, quizId));
        try {
            var proctoringOutcome = dependencies.purgeBatch(dependencies.proctoringBaseUrl(), command);
            var assessmentOutcome = dependencies.purgeBatch(dependencies.assessmentBaseUrl(), command);
            for (var outcome : assessmentOutcome) {
                if ("BLOCKED_BY_RETENTION".equals(outcome.status())) {
                    park(operation, QuizDeletionState.PURGING, "blocked-by-retention");
                    return;
                }
                if (!"PURGED".equals(outcome.status())) {
                    park(operation, QuizDeletionState.PURGING, "waiting-dependency-purge");
                    return;
                }
            }
            for (var outcome : proctoringOutcome) {
                if (!"PURGED".equals(outcome.status())) {
                    park(operation, QuizDeletionState.PURGING, "waiting-dependency-purge");
                    return;
                }
            }
        } catch (RuntimeException unavailable) {
            park(operation, QuizDeletionState.PURGING, "dependency-unavailable");
            return;
        }
        // Truthful status pull instead of trusting a single batch answer.
        var assessmentStatus = dependencies.purgeStatus(dependencies.assessmentBaseUrl(), quizId);
        var proctoringStatus = dependencies.purgeStatus(dependencies.proctoringBaseUrl(), quizId);
        if (assessmentStatus == null
                || !assessmentStatus.purged()
                || assessmentStatus.retentionHoldActive()
                || proctoringStatus == null
                || !proctoringStatus.purged()) {
            park(operation, QuizDeletionState.PURGING, "waiting-dependency-purge");
            return;
        }
        primaryPurge(operation);
    }

    private void primaryPurge(QuizDeletionOperation operation) {
        // Hard-delete quiz-owned content in dependency-safe order (versions, drafts, quiz);
        // the minimal operation ledger stays as the anti-resurrection tombstone.
        quizRepository.purgeQuizContent(operation.quizId());
        deletionOperationService.advanceState(operation.id(), QuizDeletionState.PRIMARY_PURGED, null);
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
            if (!operation.status().equals(parking)) {
                deletionOperationService.advanceState(operation.id(), parking, reason);
            }
        } catch (IllegalDeletionTransitionException ignored) {
            // Another worker advanced the same operation concurrently.
        }
    }
}
