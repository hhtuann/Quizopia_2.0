package com.quizopia.quiz.persistence;

import com.quizopia.quiz.application.QuizRepository;
import com.quizopia.quiz.domain.Quiz;
import jakarta.persistence.EntityManager;
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
}
