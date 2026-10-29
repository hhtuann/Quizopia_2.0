package com.quizopia.quiz.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record QuizDeletionRequest(
        @Schema(
                        maxLength = 300,
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description =
                                "Exact trimmed title; explicit empty string for an empty or null historical title.")
                String confirmTitle) {}
