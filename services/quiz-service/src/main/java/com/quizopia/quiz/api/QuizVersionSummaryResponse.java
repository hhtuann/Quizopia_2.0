package com.quizopia.quiz.api;

import com.quizopia.quiz.application.QuizVersionSummary;
import java.time.Instant;
import java.util.UUID;

public record QuizVersionSummaryResponse(
        UUID id,
        UUID quizId,
        int versionNumber,
        String titleSnapshot,
        String descriptionSnapshot,
        int contentSchemaVersion,
        Instant createdAt) {
    static QuizVersionSummaryResponse from(QuizVersionSummary version) {
        return new QuizVersionSummaryResponse(
                version.id(),
                version.quizId(),
                version.versionNumber(),
                version.titleSnapshot(),
                version.descriptionSnapshot(),
                version.contentSchemaVersion(),
                version.createdAt());
    }
}
