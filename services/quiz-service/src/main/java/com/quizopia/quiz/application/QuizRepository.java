package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.Quiz;
import java.util.Optional;
import java.util.UUID;

/** Quiz-owned persistence boundary for stable quiz identities. */
public interface QuizRepository {
    void insert(Quiz quiz);

    Optional<Quiz> findById(UUID id);
}
