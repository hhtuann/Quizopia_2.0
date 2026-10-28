package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizDeletionOperation;
import com.quizopia.quiz.domain.QuizDeletionState;
import java.util.Objects;
import java.util.UUID;

public final class DeletionOperationServiceAccess {
    private final DeletionOperationService service;

    public DeletionOperationServiceAccess(DeletionOperationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public QuizDeletionOperation advanceState(UUID operationId, QuizDeletionState newState, String reason) {
        return service.advanceState(operationId, newState, reason);
    }
}
