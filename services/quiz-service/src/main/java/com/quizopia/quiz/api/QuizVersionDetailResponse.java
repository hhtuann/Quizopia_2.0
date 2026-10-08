package com.quizopia.quiz.api;

import com.quizopia.quiz.domain.QuizVersion;
import com.quizopia.quiz.domain.markdown.QuizContent;
import java.time.Instant;
import java.util.UUID;

public record QuizVersionDetailResponse(
        UUID id,
        UUID quizId,
        int versionNumber,
        String titleSnapshot,
        String descriptionSnapshot,
        String sourceSnapshot,
        QuizContent structuredContent,
        int contentSchemaVersion,
        Instant createdAt) {
    static QuizVersionDetailResponse from(QuizVersion version) {
        return new QuizVersionDetailResponse(
                version.id(),
                version.quizId(),
                version.versionNumber(),
                version.titleSnapshot(),
                version.descriptionSnapshot(),
                version.sourceSnapshot(),
                version.structuredContent(),
                version.contentSchemaVersion(),
                version.createdAt());
    }
}
