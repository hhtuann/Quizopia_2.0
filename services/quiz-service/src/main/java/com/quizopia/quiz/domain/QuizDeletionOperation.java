package com.quizopia.quiz.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable owner-initiated deletion operation for one quiz, with its frozen version inventory. */
public record QuizDeletionOperation(
        UUID id,
        UUID quizId,
        UUID ownerUserId,
        String idempotencyKey,
        QuizDeletionState status,
        int mainProgress,
        Instant createdAt,
        Instant updatedAt,
        String confirmedTitle,
        List<VersionRef> versionInventory,
        String lastReason) {
    public QuizDeletionOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(quizId, "quizId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(confirmedTitle, "confirmedTitle");
        versionInventory = List.copyOf(Objects.requireNonNull(versionInventory, "versionInventory"));
    }

    public QuizDeletionOperation withState(QuizDeletionState newState, String reason, Instant changedAt) {
        int nextProgress = newState.isMain() ? Math.max(mainProgress, newState.mainOrder()) : mainProgress;
        return new QuizDeletionOperation(
                id,
                quizId,
                ownerUserId,
                idempotencyKey,
                newState,
                nextProgress,
                createdAt,
                changedAt,
                confirmedTitle,
                versionInventory,
                reason != null ? reason : lastReason);
    }

    public record VersionRef(UUID id, int versionNumber) {
        public VersionRef {
            Objects.requireNonNull(id, "id");
            if (versionNumber < 1) {
                throw new IllegalArgumentException("Version number must be positive");
            }
        }
    }
}
