package com.quizopia.quiz.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Current mutable authoring state associated with one stable quiz identity. */
public record QuizDraft(UUID quizId, String title, String description, String authoringSource, Instant updatedAt) {
    public QuizDraft {
        Objects.requireNonNull(quizId, "quizId");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
