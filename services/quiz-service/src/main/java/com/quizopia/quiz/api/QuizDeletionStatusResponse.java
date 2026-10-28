package com.quizopia.quiz.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QuizDeletionStatusResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID operationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(types = {"string", "null"}) String lastReason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> stages) {
    public static QuizDeletionStatusResponse from(com.quizopia.quiz.domain.QuizDeletionOperation operation) {
        return new QuizDeletionStatusResponse(
                operation.id(),
                operation.status().name(),
                operation.createdAt(),
                operation.updatedAt(),
                operation.lastReason(),
                com.quizopia.quiz.domain.QuizDeletionState.MAIN_ORDER.stream()
                        .map(state -> state.name())
                        .toList());
    }
}
