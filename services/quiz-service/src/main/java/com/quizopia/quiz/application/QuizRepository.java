package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.Quiz;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Quiz-owned persistence boundary for stable quiz identities. */
public interface QuizRepository {
    void insert(Quiz quiz);

    Optional<Quiz> findById(UUID id);

    Optional<Quiz> findByIdForUpdate(UUID id);

    void markDeletionFenced(UUID quizId, Instant fencedAt);

    /**
     * FB-015 hard purge of quiz-owned content in dependency-safe order (versions, drafts,
     * quiz row). The deletion-operation ledger is NOT content and survives as the tombstone.
     */
    void purgeQuizContent(UUID quizId);
}
