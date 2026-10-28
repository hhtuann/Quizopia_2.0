package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizDeletionState;
import java.time.Instant;
import java.util.UUID;

public record QuizLibraryItem(
        UUID quizId,
        String title,
        String description,
        Instant createdAt,
        Instant updatedAt,
        Integer latestVersionNumber,
        QuizDeletionState deletionStatus) {
    public QuizLibraryItem(
            UUID quizId,
            String title,
            String description,
            Instant createdAt,
            Instant updatedAt,
            Integer latestVersionNumber) {
        this(quizId, title, description, createdAt, updatedAt, latestVersionNumber, null);
    }
}
