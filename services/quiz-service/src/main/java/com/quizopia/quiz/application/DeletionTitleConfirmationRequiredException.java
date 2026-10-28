package com.quizopia.quiz.application;

import java.util.Objects;
import java.util.UUID;

public final class DeletionTitleConfirmationRequiredException extends RuntimeException {
    private final UUID quizId;

    public DeletionTitleConfirmationRequiredException(UUID quizId) {
        super("Exact quiz title confirmation is required");
        this.quizId = Objects.requireNonNull(quizId, "quizId");
    }

    public UUID quizId() {
        return quizId;
    }
}
