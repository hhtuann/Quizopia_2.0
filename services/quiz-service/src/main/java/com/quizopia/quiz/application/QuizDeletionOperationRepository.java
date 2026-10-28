package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizDeletionOperation;
import com.quizopia.quiz.domain.QuizDeletionState;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Quiz-owned persistence boundary for durable deletion operations. */
public interface QuizDeletionOperationRepository {
    void insert(QuizDeletionOperation operation);

    void update(QuizDeletionOperation operation);

    Optional<QuizDeletionOperation> findById(UUID id);

    Optional<QuizDeletionOperation> findByIdForUpdate(UUID id);

    Optional<QuizDeletionOperation> findByQuizIdAndIdempotencyKey(UUID quizId, String idempotencyKey);

    Optional<QuizDeletionOperation> findLatestByQuizId(UUID quizId);

    Map<UUID, QuizDeletionState> findLatestStatusesByQuizIdIn(Collection<UUID> quizIds);
}
