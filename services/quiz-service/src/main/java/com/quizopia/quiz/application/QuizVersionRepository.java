package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Quiz-owned persistence boundary for immutable published versions. */
public interface QuizVersionRepository {
    void insert(QuizVersion version);

    Optional<QuizVersion> findLatestByQuizId(UUID quizId);

    List<QuizVersionSummary> findByQuizIdBeforeVersionNumber(UUID quizId, Integer beforeVersionNumber, int fetchLimit);

    Optional<QuizVersion> findByQuizIdAndVersionNumber(UUID quizId, int versionNumber);
}
