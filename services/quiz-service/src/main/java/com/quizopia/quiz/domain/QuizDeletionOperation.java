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
        String lastReason,
        String requestDigest) {
    public QuizDeletionOperation(
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
        this(
                id,
                quizId,
                ownerUserId,
                idempotencyKey,
                status,
                mainProgress,
                createdAt,
                updatedAt,
                confirmedTitle,
                versionInventory,
                lastReason,
                null);
    }

    public QuizDeletionOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(quizId, "quizId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        // confirmedTitle is NULL after the irreversible-boundary scrub (H4): the
        // ledger intentionally no longer retains quiz text once PRIMARY_PURGED.
        versionInventory = List.copyOf(Objects.requireNonNull(versionInventory, "versionInventory"));
    }

    /** Minimal ledger view after the H4 scrub: the inventory reads back as empty. */
    public boolean contentScrubbed() {
        return confirmedTitle == null;
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
                reason != null ? reason : lastReason,
                requestDigest);
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
