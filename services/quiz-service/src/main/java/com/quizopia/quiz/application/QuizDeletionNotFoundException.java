package com.quizopia.quiz.application;

import java.util.Objects;
import java.util.UUID;

public final class QuizDeletionNotFoundException extends RuntimeException {
    private final UUID operationId;

    public QuizDeletionNotFoundException(UUID operationId) {
        super("Quiz deletion operation was not found");
        this.operationId = Objects.requireNonNull(operationId, "operationId");
    }

    public UUID operationId() {
        return operationId;
    }
}
