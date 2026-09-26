package com.quizopia.quiz.api;

import com.quizopia.quiz.application.QuizDraftDetails;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record QuizDraftResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID quizId,
        @Schema(types = {"string", "null"}) String title,
        @Schema(types = {"string", "null"}) String description,
        @Schema(types = {"string", "null"}) String authoringSource,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {
    public static QuizDraftResponse from(QuizDraftDetails details) {
        return new QuizDraftResponse(
                details.quiz().id(),
                details.draft().title(),
                details.draft().description(),
                details.draft().authoringSource(),
                details.quiz().createdAt(),
                details.draft().updatedAt());
    }
}
