package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizDeletionOperation;
import com.quizopia.quiz.domain.QuizDeletionState;
import java.util.Collection;
import java.util.List;
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

    /** Nonterminal operations the saga still owes work for, oldest first. */
    List<QuizDeletionOperation> findSagaCandidates(int limit);

    /**
     * H4 scrub: nulls confirmed_title and reduces version_inventory to
     * {"versionCount":n}. Called in the same transaction as the irreversible
     * PRIMARY_PURGED advance; idempotent.
     */
    void scrubLedgerContent(UUID operationId);
}
