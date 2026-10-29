package com.quizopia.quiz.persistence;

import com.quizopia.quiz.application.QuizDeletionOperationRepository;
import com.quizopia.quiz.domain.QuizDeletionOperation;
import com.quizopia.quiz.domain.QuizDeletionState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
@Transactional(readOnly = true)
public class JpaQuizDeletionOperationRepository implements QuizDeletionOperationRepository {
    private final EntityManager entityManager;
    private final DeletionJson deletionJson;

    public JpaQuizDeletionOperationRepository(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.deletionJson = new DeletionJson(objectMapper);
    }

    @Override
    @Transactional
    public void insert(QuizDeletionOperation operation) {
        entityManager.persist(
                new QuizDeletionOperationEntity(operation, deletionJson.writeInventory(operation.versionInventory())));
        entityManager.flush();
    }

    @Override
    @Transactional
    public void update(QuizDeletionOperation operation) {
        QuizDeletionOperationEntity entity =
                entityManager.find(QuizDeletionOperationEntity.class, operation.id(), LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) {
            throw new IllegalArgumentException("Deletion operation row is missing for update");
        }
        entity.apply(operation);
        entityManager.flush();
    }

    @Override
    public Optional<QuizDeletionOperation> findById(UUID id) {
        return Optional.ofNullable(entityManager.find(QuizDeletionOperationEntity.class, id))
                .map(entity -> entity.toDomain(deletionJson));
    }

    @Override
    @Transactional
    public Optional<QuizDeletionOperation> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(
                        entityManager.find(QuizDeletionOperationEntity.class, id, LockModeType.PESSIMISTIC_WRITE))
                .map(entity -> entity.toDomain(deletionJson));
    }

    @Override
    public Optional<QuizDeletionOperation> findByQuizIdAndIdempotencyKey(UUID quizId, String idempotencyKey) {
        return entityManager
                .createQuery(
                        "select o from QuizDeletionOperationEntity o"
                                + " where o.quizId = :quizId and o.idempotencyKey = :idempotencyKey",
                        QuizDeletionOperationEntity.class)
                .setParameter("quizId", quizId)
                .setParameter("idempotencyKey", idempotencyKey)
                .getResultStream()
                .findFirst()
                .map(entity -> entity.toDomain(deletionJson));
    }

    @Override
    public Optional<QuizDeletionOperation> findLatestByQuizId(UUID quizId) {
        return entityManager
                .createQuery(
                        "select o from QuizDeletionOperationEntity o where o.quizId = :quizId order by o.updatedAt desc",
                        QuizDeletionOperationEntity.class)
                .setParameter("quizId", quizId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(entity -> entity.toDomain(deletionJson));
    }

    @Override
    public Map<UUID, QuizDeletionState> findLatestStatusesByQuizIdIn(Collection<UUID> quizIds) {
        if (quizIds.isEmpty()) {
            return Map.of();
        }
        TypedQuery<QuizDeletionOperationEntity> query = entityManager.createQuery(
                "select o from QuizDeletionOperationEntity o where o.quizId in :quizIds",
                QuizDeletionOperationEntity.class);
        List<QuizDeletionOperationEntity> rows =
                query.setParameter("quizIds", quizIds).getResultList();
        Map<UUID, QuizDeletionOperation> latest = new HashMap<>();
        for (QuizDeletionOperationEntity row : rows) {
            QuizDeletionOperation operation = row.toDomain(deletionJson);
            latest.merge(
                    operation.quizId(),
                    operation,
                    (current, candidate) -> candidate.updatedAt().isAfter(current.updatedAt()) ? candidate : current);
        }
        Map<UUID, QuizDeletionState> statuses = new LinkedHashMap<>();
        latest.forEach((quizId, operation) -> statuses.put(quizId, operation.status()));
        return statuses;
    }

    @Override
    public List<QuizDeletionOperation> findSagaCandidates(int limit) {
        return entityManager
                .createQuery(
                        "select o from QuizDeletionOperationEntity o"
                                + " where o.mainProgress < :terminalProgress order by o.updatedAt asc",
                        QuizDeletionOperationEntity.class)
                .setParameter("terminalProgress", QuizDeletionState.PRIMARY_PURGED.mainOrder())
                .setMaxResults(limit)
                .getResultStream()
                .map(entity -> entity.toDomain(deletionJson))
                .filter(operation -> !operation.status().equals(QuizDeletionState.PENDING_POLICY)
                        && !operation.status().equals(QuizDeletionState.RETRYABLE_FAILURE))
                .toList();
    }

    @Override
    @Transactional
    public void scrubLedgerContent(UUID operationId) {
        entityManager
                .createNativeQuery("update quiz_deletion_operations"
                        + " set confirmed_title = null,"
                        + " version_inventory = case when jsonb_typeof(version_inventory) = 'array'"
                        + " then jsonb_build_object('versionCount', jsonb_array_length(version_inventory))"
                        + " else version_inventory end"
                        + " where id = :operationId")
                .setParameter("operationId", operationId)
                .executeUpdate();
        entityManager.clear();
    }
}
