package com.quizopia.quiz.persistence;

import com.quizopia.quiz.application.QuizVersionRepository;
import com.quizopia.quiz.application.QuizVersionSummary;
import com.quizopia.quiz.domain.QuizVersion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
@Transactional(readOnly = true)
public class JpaQuizVersionRepository implements QuizVersionRepository {
    private final EntityManager entityManager;
    private final QuizStructuredContentJson structuredContentJson;

    public JpaQuizVersionRepository(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.structuredContentJson = new QuizStructuredContentJson(objectMapper);
    }

    @Override
    @Transactional
    public void insert(QuizVersion version) {
        entityManager.persist(new QuizVersionEntity(version, structuredContentJson.write(version.structuredContent())));
        entityManager.flush();
    }

    @Override
    public Optional<QuizVersion> findLatestByQuizId(UUID quizId) {
        return entityManager
                .createQuery(
                        "select v from QuizVersionEntity v where v.quizId = :quizId order by v.versionNumber desc",
                        QuizVersionEntity.class)
                .setParameter("quizId", quizId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(entity -> entity.toDomain(structuredContentJson.read(entity.structuredContent())));
    }

    @Override
    public List<QuizVersionSummary> findByQuizIdBeforeVersionNumber(
            UUID quizId, Integer beforeVersionNumber, int fetchLimit) {
        String cursorPredicate = beforeVersionNumber == null ? "" : " and v.versionNumber < :beforeVersionNumber";
        TypedQuery<QuizVersionSummary> query = entityManager
                .createQuery(
                        """
                        select new com.quizopia.quiz.application.QuizVersionSummary(
                            v.id, v.quizId, v.versionNumber, v.titleSnapshot, v.descriptionSnapshot,
                            v.contentSchemaVersion, v.createdAt)
                        from QuizVersionEntity v
                        where v.quizId = :quizId
                        """
                                + cursorPredicate
                                + " order by v.versionNumber desc",
                        QuizVersionSummary.class)
                .setParameter("quizId", quizId)
                .setMaxResults(fetchLimit);
        if (beforeVersionNumber != null) {
            query.setParameter("beforeVersionNumber", beforeVersionNumber);
        }
        return query.getResultList();
    }

    @Override
    public Optional<QuizVersion> findByQuizIdAndVersionNumber(UUID quizId, int versionNumber) {
        return entityManager
                .createQuery(
                        "select v from QuizVersionEntity v where v.quizId = :quizId and v.versionNumber = :versionNumber",
                        QuizVersionEntity.class)
                .setParameter("quizId", quizId)
                .setParameter("versionNumber", versionNumber)
                .getResultStream()
                .findFirst()
                .map(entity -> entity.toDomain(structuredContentJson.read(entity.structuredContent())));
    }
}
