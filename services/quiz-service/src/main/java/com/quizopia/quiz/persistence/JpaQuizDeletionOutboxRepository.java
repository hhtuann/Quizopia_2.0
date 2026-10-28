package com.quizopia.quiz.persistence;

import com.quizopia.quiz.application.QuizDeletionOutboxRepository;
import com.quizopia.quiz.domain.QuizDeletionOutboxEntry;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
@Transactional(readOnly = true)
public class JpaQuizDeletionOutboxRepository implements QuizDeletionOutboxRepository {
    private final EntityManager entityManager;
    private final DeletionJson deletionJson;

    public JpaQuizDeletionOutboxRepository(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.deletionJson = new DeletionJson(objectMapper);
    }

    @Override
    @Transactional
    public void insert(QuizDeletionOutboxEntry entry) {
        entityManager.persist(new QuizDeletionOutboxEntity(entry, deletionJson.writePayload(entry)));
        entityManager.flush();
    }

    @Override
    public List<QuizDeletionOutboxEntry> findDue(Instant now, int fetchLimit) {
        return entityManager
                .createQuery(
                        "select o from QuizDeletionOutboxEntity o"
                                + " where o.dispatchedAt is null and o.attempts < "
                                + MAX_ATTEMPTS
                                + " and o.nextAttemptAt <= :now order by o.nextAttemptAt asc",
                        QuizDeletionOutboxEntity.class)
                .setParameter("now", now)
                .setMaxResults(fetchLimit)
                .getResultList()
                .stream()
                .map(entity -> entity.toDomain(deletionJson))
                .toList();
    }

    @Override
    @Transactional
    public void markDispatched(UUID id, Instant dispatchedAt) {
        entityManager
                .createQuery("update QuizDeletionOutboxEntity o set o.dispatchedAt = :dispatchedAt"
                        + " where o.id = :id and o.dispatchedAt is null")
                .setParameter("dispatchedAt", dispatchedAt)
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void recordAttempt(UUID id, Instant failedAt, Instant nextAttemptAt, String error) {
        entityManager
                .createQuery("update QuizDeletionOutboxEntity o"
                        + " set o.attempts = o.attempts + 1, o.nextAttemptAt = :nextAttemptAt,"
                        + " o.lastError = :error, o.dispatchedAt = null"
                        + " where o.id = :id and o.dispatchedAt is null")
                .setParameter("nextAttemptAt", nextAttemptAt)
                .setParameter("error", error)
                .setParameter("id", id)
                .executeUpdate();
    }
}
