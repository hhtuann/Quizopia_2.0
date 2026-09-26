package com.quizopia.quiz.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record QuizDraftRequest(
        @Schema(types = {"string", "null"}) String title,
        @Schema(types = {"string", "null"}) String description,
        @Schema(types = {"string", "null"}) String authoringSource) {}
