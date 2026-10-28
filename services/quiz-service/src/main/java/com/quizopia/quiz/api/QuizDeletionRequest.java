package com.quizopia.quiz.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record QuizDeletionRequest(
        @Schema(minLength = 1, maxLength = 300, requiredMode = Schema.RequiredMode.REQUIRED) String confirmTitle) {}
