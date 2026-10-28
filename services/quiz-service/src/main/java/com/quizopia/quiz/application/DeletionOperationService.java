package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.Quiz;
import com.quizopia.quiz.domain.QuizDeletionOperation;
import com.quizopia.quiz.domain.QuizDeletionOutboxEntry;
import com.quizopia.quiz.domain.QuizDeletionState;
import com.quizopia.quiz.domain.QuizDraft;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeletionOperationService {
    private final QuizRepository quizRepository;
    private final QuizDraftRepository quizDraftRepository;
    private final QuizVersionRepository quizVersionRepository;
    private final QuizDeletionOperationRepository deletionOperationRepository;
    private final QuizDeletionOutboxRepository deletionOutboxRepository;
    private final Clock clock;

    public DeletionOperationService(
            QuizRepository quizRepository,
            QuizDraftRepository quizDraftRepository,
            QuizVersionRepository quizVersionRepository,
            QuizDeletionOperationRepository deletionOperationRepository,
            QuizDeletionOutboxRepository deletionOutboxRepository,
            Clock clock) {
        this.quizRepository = Objects.requireNonNull(quizRepository, "quizRepository");
        this.quizDraftRepository = Objects.requireNonNull(quizDraftRepository, "quizDraftRepository");
        this.quizVersionRepository = Objects.requireNonNull(quizVersionRepository, "quizVersionRepository");
        this.deletionOperationRepository =
                Objects.requireNonNull(deletionOperationRepository, "deletionOperationRepository");
        this.deletionOutboxRepository = Objects.requireNonNull(deletionOutboxRepository, "deletionOutboxRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public DeletionRequestResult requestDeletion(
            UUID callerUserId, UUID quizId, String idempotencyKey, String confirmTitle) {
        Objects.requireNonNull(callerUserId, "callerUserId");
        Objects.requireNonNull(quizId, "quizId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(confirmTitle, "confirmTitle");

        Quiz quiz = requireOwnedQuizForUpdate(callerUserId, quizId);
        String canonicalTitle = confirmTitle.trim();
        Optional<QuizDeletionOperation> replay =
                deletionOperationRepository.findByQuizIdAndIdempotencyKey(quizId, idempotencyKey);
        if (replay.isPresent()) {
            QuizDeletionOperation operation = replay.get();
            if (operation.confirmedTitle().equals(canonicalTitle)) {
                return new DeletionRequestResult(operation.id(), operation.status(), false);
            }
            throw new DeletionConflictException(quizId);
        }
        if (quiz.isDeletionFenced()) {
            throw new DeletionConflictException(quizId);
        }
        requireExactTitleConfirmation(quizId, canonicalTitle);

        Instant now = clock.instant();
        QuizDeletionOperation requested = new QuizDeletionOperation(
                UUID.randomUUID(),
                quizId,
                callerUserId,
                idempotencyKey,
                QuizDeletionState.REQUESTED,
                QuizDeletionState.REQUESTED.mainOrder(),
                now,
                now,
                canonicalTitle,
                frozenVersionInventory(quizId),
                null);
        deletionOperationRepository.insert(requested);
        QuizDeletionOperation fenced = advanceState(requested.id(), QuizDeletionState.QUIZ_FENCED, null);
        quizRepository.markDeletionFenced(quizId, now);
        deletionOutboxRepository.insert(QuizDeletionOutboxEntry.requested(UUID.randomUUID(), fenced, now, now));
        return new DeletionRequestResult(fenced.id(), fenced.status(), true);
    }

    @Transactional(readOnly = true)
    public QuizDeletionOperation getOperation(UUID callerUserId, UUID quizId, UUID operationId) {
        Objects.requireNonNull(callerUserId, "callerUserId");
        Objects.requireNonNull(quizId, "quizId");
        Objects.requireNonNull(operationId, "operationId");

        QuizDeletionOperation operation = deletionOperationRepository
                .findById(operationId)
                .orElseThrow(() -> new QuizDeletionNotFoundException(operationId));
        if (!operation.quizId().equals(quizId) || !operation.ownerUserId().equals(callerUserId)) {
            throw new QuizDeletionNotFoundException(operationId);
        }
        return operation;
    }

    @Transactional
    QuizDeletionOperation advanceState(UUID operationId, QuizDeletionState newState, String reason) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(newState, "newState");

        QuizDeletionOperation operation = deletionOperationRepository
                .findByIdForUpdate(operationId)
                .orElseThrow(() -> new QuizDeletionNotFoundException(operationId));
        if (!QuizDeletionState.isForwardTransition(operation.status(), operation.mainProgress(), newState)) {
            throw new IllegalDeletionTransitionException(operation.status(), newState);
        }
        QuizDeletionOperation updated = operation.withState(newState, reason, clock.instant());
        deletionOperationRepository.update(updated);
        return updated;
    }

    private Quiz requireOwnedQuizForUpdate(UUID callerUserId, UUID quizId) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId).orElseThrow(() -> new QuizNotFoundException(quizId));
        if (!quiz.isOwnedBy(callerUserId)) {
            throw new QuizOwnershipDeniedException(quizId, callerUserId);
        }
        return quiz;
    }

    private void requireExactTitleConfirmation(UUID quizId, String canonicalTitle) {
        QuizDraft draft = quizDraftRepository
                .findByQuizId(quizId)
                .orElseThrow(() -> new DeletionTitleConfirmationRequiredException(quizId));
        String currentTitle = draft.title();
        if (currentTitle == null || !canonicalTitle.equals(currentTitle.trim())) {
            throw new DeletionTitleConfirmationRequiredException(quizId);
        }
    }

    private List<QuizDeletionOperation.VersionRef> frozenVersionInventory(UUID quizId) {
        return quizVersionRepository.findByQuizIdBeforeVersionNumber(quizId, null, Integer.MAX_VALUE).stream()
                .map(summary -> new QuizDeletionOperation.VersionRef(summary.id(), summary.versionNumber()))
                .sorted(Comparator.comparingInt(QuizDeletionOperation.VersionRef::versionNumber))
                .toList();
    }
}
