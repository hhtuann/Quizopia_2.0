package com.quizopia.quiz.application;

import java.util.Objects;
import java.util.UUID;

public final class QuizNotFoundException extends RuntimeException {
    private final UUID quizId;

    public QuizNotFoundException(UUID quizId) {
        super("Quiz was not found");
        this.quizId = Objects.requireNonNull(quizId, "quizId");
    }

    public UUID quizId() {
        return quizId;
    }
}
