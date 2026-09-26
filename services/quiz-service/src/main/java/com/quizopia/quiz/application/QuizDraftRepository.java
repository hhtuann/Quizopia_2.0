package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizDraft;
import java.util.Optional;
import java.util.UUID;

/** Quiz-owned persistence boundary for current mutable draft authoring state. */
public interface QuizDraftRepository {
    void save(QuizDraft draft);

    Optional<QuizDraft> findByQuizId(UUID quizId);
}
