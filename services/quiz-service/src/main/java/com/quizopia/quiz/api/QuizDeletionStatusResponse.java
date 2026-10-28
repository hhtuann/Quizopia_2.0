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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> stages,
        @Schema(
                        types = {"string", "null"},
                        description = "Copy-expiry note. Present only at PRIMARY_PURGED: primary data is gone while "
                                + "backups/media-provider copies await verified erasure, so the operation is "
                                + "deliberately not reported as ALL_COPIES_ERASED.")
                String note) {
    public static QuizDeletionStatusResponse from(com.quizopia.quiz.domain.QuizDeletionOperation operation) {
        return new QuizDeletionStatusResponse(
                operation.id(),
                operation.status().name(),
                operation.createdAt(),
                operation.updatedAt(),
                operation.lastReason(),
                com.quizopia.quiz.domain.QuizDeletionState.MAIN_ORDER.stream()
                        .map(state -> state.name())
                        .toList(),
                // Honest copies-pending note: ALL_COPIES_ERASED is unreachable in this PR.
                operation.status() == com.quizopia.quiz.domain.QuizDeletionState.PRIMARY_PURGED
                        ? com.quizopia.quiz.application.DeletionSagaService.COPIES_PENDING_NOTE
                        : null);
    }
}
