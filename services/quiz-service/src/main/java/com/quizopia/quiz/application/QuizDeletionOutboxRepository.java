package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizDeletionOutboxEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Quiz-owned persistence boundary for the deletion transactional outbox. */
public interface QuizDeletionOutboxRepository {
    int MAX_ATTEMPTS = 10;

    void insert(QuizDeletionOutboxEntry entry);

    List<QuizDeletionOutboxEntry> findDue(Instant now, int fetchLimit);

    void markDispatched(UUID id, Instant dispatchedAt);

    void recordAttempt(UUID id, Instant failedAt, Instant nextAttemptAt, String error);
}
