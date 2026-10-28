package com.quizopia.quiz.application;

import java.util.Objects;
import java.util.UUID;

public final class DeletionConflictException extends RuntimeException {
    private final UUID quizId;

    public DeletionConflictException(UUID quizId) {
        super("Deletion request conflicts with an existing operation");
        this.quizId = Objects.requireNonNull(quizId, "quizId");
    }

    public UUID quizId() {
        return quizId;
    }
}
