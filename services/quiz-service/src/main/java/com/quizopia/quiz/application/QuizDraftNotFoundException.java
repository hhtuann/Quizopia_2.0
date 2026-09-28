package com.quizopia.quiz.application;

import java.util.Objects;
import java.util.UUID;

public final class QuizDraftNotFoundException extends RuntimeException {
    private final UUID quizId;

    public QuizDraftNotFoundException(UUID quizId) {
        super("Quiz draft was not found");
        this.quizId = Objects.requireNonNull(quizId, "quizId");
    }

    public UUID quizId() {
        return quizId;
    }
}
