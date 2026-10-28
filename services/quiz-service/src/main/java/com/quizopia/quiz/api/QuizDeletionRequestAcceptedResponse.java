package com.quizopia.quiz.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record QuizDeletionRequestAcceptedResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID operationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status) {}
