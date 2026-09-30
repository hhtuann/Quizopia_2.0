package com.quizopia.quiz.domain;

import com.quizopia.quiz.domain.markdown.QuizContent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable published snapshot of Quiz-owned authoring content. */
public record QuizVersion(
        UUID id,
        UUID quizId,
        int versionNumber,
        String titleSnapshot,
        String descriptionSnapshot,
        String sourceSnapshot,
        QuizContent structuredContent,
        int contentSchemaVersion,
        Instant createdAt) {
    public static final int CURRENT_CONTENT_SCHEMA_VERSION = 1;

    public QuizVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(quizId, "quizId");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        Objects.requireNonNull(sourceSnapshot, "sourceSnapshot");
        Objects.requireNonNull(structuredContent, "structuredContent");
        if (contentSchemaVersion != CURRENT_CONTENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported contentSchemaVersion: " + contentSchemaVersion);
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
