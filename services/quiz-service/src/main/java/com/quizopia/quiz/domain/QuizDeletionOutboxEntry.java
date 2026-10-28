package com.quizopia.quiz.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Transactional-outbox record for one durable deletion command; carries no quiz content. */
public record QuizDeletionOutboxEntry(
        UUID id,
        UUID operationId,
        UUID quizId,
        UUID ownerUserId,
        String kind,
        List<QuizDeletionOperation.VersionRef> versionInventory,
        Instant createdAt,
        Instant nextAttemptAt,
        int attempts,
        Instant dispatchedAt,
        String lastError) {
    public static final String KIND_QUIZ_DELETION_REQUESTED = "quiz.deletion.requested.v1";

    public QuizDeletionOutboxEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(quizId, "quizId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(kind, "kind");
        versionInventory = List.copyOf(Objects.requireNonNull(versionInventory, "versionInventory"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (attempts < 0) {
            throw new IllegalArgumentException("Attempts must not be negative");
        }
    }

    public static QuizDeletionOutboxEntry requested(
            UUID id, QuizDeletionOperation operation, Instant createdAt, Instant nextAttemptAt) {
        return new QuizDeletionOutboxEntry(
                id,
                operation.id(),
                operation.quizId(),
                operation.ownerUserId(),
                KIND_QUIZ_DELETION_REQUESTED,
                operation.versionInventory(),
                createdAt,
                nextAttemptAt,
                0,
                null,
                null);
    }
}
