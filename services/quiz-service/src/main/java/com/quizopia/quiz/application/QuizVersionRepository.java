package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizVersion;
import java.util.Optional;
import java.util.UUID;

/** Quiz-owned persistence boundary for immutable published versions. */
public interface QuizVersionRepository {
    void insert(QuizVersion version);

    Optional<QuizVersion> findLatestByQuizId(UUID quizId);
}
