package com.quizopia.quiz.persistence;

import com.quizopia.quiz.application.QuizRepository;
import com.quizopia.quiz.domain.Quiz;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaQuizRepository implements QuizRepository {
    private final EntityManager entityManager;

    public JpaQuizRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void insert(Quiz quiz) {
        entityManager.persist(new QuizEntity(quiz));
        entityManager.flush();
    }

    @Override
    public Optional<Quiz> findById(UUID id) {
        return Optional.ofNullable(entityManager.find(QuizEntity.class, id)).map(QuizEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<Quiz> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(entityManager.find(QuizEntity.class, id, LockModeType.PESSIMISTIC_WRITE))
                .map(QuizEntity::toDomain);
    }

    @Override
    @Transactional
    public void markDeletionFenced(UUID quizId, Instant fencedAt) {
        QuizEntity entity = entityManager.find(QuizEntity.class, quizId, LockModeType.PESSIMISTIC_WRITE);
        if (entity != null) {
            entity.markDeletionFenced(fencedAt);
            entityManager.flush();
        }
    }

    @Override
    @Transactional
    public void purgeQuizContent(UUID quizId) {
        // Dependency-safe order inside one transaction; the operation ledger survives (V5).
        entityManager
                .createQuery("delete from QuizVersionEntity v where v.quizId = :quizId")
                .setParameter("quizId", quizId)
                .executeUpdate();
        entityManager
                .createQuery("delete from QuizDraftEntity d where d.quizId = :quizId")
                .setParameter("quizId", quizId)
                .executeUpdate();
        QuizEntity quiz = entityManager.find(QuizEntity.class, quizId, LockModeType.PESSIMISTIC_WRITE);
        if (quiz != null) {
            entityManager.remove(quiz);
        }
        entityManager.flush();
    }
}
