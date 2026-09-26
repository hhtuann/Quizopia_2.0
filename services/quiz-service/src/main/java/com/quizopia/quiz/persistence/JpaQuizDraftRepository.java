package com.quizopia.quiz.persistence;

import com.quizopia.quiz.application.QuizDraftRepository;
import com.quizopia.quiz.domain.QuizDraft;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaQuizDraftRepository implements QuizDraftRepository {
    private final EntityManager entityManager;

    public JpaQuizDraftRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void save(QuizDraft draft) {
        entityManager.merge(new QuizDraftEntity(draft));
        entityManager.flush();
    }

    @Override
    public Optional<QuizDraft> findByQuizId(UUID quizId) {
        return Optional.ofNullable(entityManager.find(QuizDraftEntity.class, quizId))
                .map(QuizDraftEntity::toDomain);
    }
}
