package com.quizopia.quiz.application;

import java.time.Instant;
import java.util.UUID;

public record QuizVersionSummary(
        UUID id,
        UUID quizId,
        int versionNumber,
        String titleSnapshot,
        String descriptionSnapshot,
        int contentSchemaVersion,
        Instant createdAt) {}
