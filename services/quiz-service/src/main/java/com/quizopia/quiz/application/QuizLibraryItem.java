package com.quizopia.quiz.application;

import java.time.Instant;
import java.util.UUID;

public record QuizLibraryItem(
        UUID quizId,
        String title,
        String description,
        Instant createdAt,
        Instant updatedAt,
        Integer latestVersionNumber) {}
