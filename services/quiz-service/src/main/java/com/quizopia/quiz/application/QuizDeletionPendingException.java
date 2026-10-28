package com.quizopia.quiz.application;

import java.util.Objects;
import java.util.UUID;

public final class QuizDeletionPendingException extends RuntimeException {
    private final UUID quizId;

    public QuizDeletionPendingException(UUID quizId) {
        super("Quiz deletion is pending");
        this.quizId = Objects.requireNonNull(quizId, "quizId");
    }

    public UUID quizId() {
        return quizId;
    }
}
