package com.quizopia.quiz.api;

import com.quizopia.quiz.application.QuizLibraryItem;
import java.time.Instant;
import java.util.UUID;

public record QuizLibraryItemResponse(
        UUID quizId,
        String title,
        String description,
        Instant createdAt,
        Instant updatedAt,
        Integer latestVersionNumber) {
    static QuizLibraryItemResponse from(QuizLibraryItem item) {
        return new QuizLibraryItemResponse(
                item.quizId(),
                item.title(),
                item.description(),
                item.createdAt(),
                item.updatedAt(),
                item.latestVersionNumber());
    }
}
