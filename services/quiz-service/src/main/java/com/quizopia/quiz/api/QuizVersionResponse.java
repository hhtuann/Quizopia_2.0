package com.quizopia.quiz.api;

import com.quizopia.quiz.domain.QuizVersion;
import java.time.Instant;
import java.util.UUID;

public record QuizVersionResponse(UUID id, UUID quizId, int versionNumber, Instant createdAt) {
    static QuizVersionResponse from(QuizVersion version) {
        return new QuizVersionResponse(version.id(), version.quizId(), version.versionNumber(), version.createdAt());
    }
}
