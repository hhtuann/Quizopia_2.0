package com.quizopia.quiz.application;

import java.util.Objects;
import java.util.UUID;

public final class QuizOwnershipDeniedException extends RuntimeException {
    private final UUID quizId;
    private final UUID callerUserId;

    public QuizOwnershipDeniedException(UUID quizId, UUID callerUserId) {
        super("Caller is not the quiz owner");
        this.quizId = Objects.requireNonNull(quizId, "quizId");
        this.callerUserId = Objects.requireNonNull(callerUserId, "callerUserId");
    }

    public UUID quizId() {
        return quizId;
    }

    public UUID callerUserId() {
        return callerUserId;
    }
}
