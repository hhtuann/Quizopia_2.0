package com.quizopia.quiz.persistence;

import com.quizopia.quiz.application.QuizLibraryItem;
import com.quizopia.quiz.application.QuizLibraryPosition;
import com.quizopia.quiz.application.QuizLibraryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaQuizLibraryRepository implements QuizLibraryRepository {
    private static final String SELECT =
            """
            select new com.quizopia.quiz.application.QuizLibraryItem(
                q.id, d.title, d.description, q.createdAt, d.updatedAt, max(v.versionNumber))
            from QuizEntity q
            join QuizDraftEntity d on d.quizId = q.id
            left join QuizVersionEntity v on v.quizId = q.id
            where q.ownerUserId = :ownerUserId
            """;

    private static final String CURSOR_PREDICATE =
            """
            and (d.updatedAt < :cursorUpdatedAt
                or (d.updatedAt = :cursorUpdatedAt and q.id < :cursorQuizId))
            """;

    private static final String ORDERING =
            """
            group by q.id, d.title, d.description, q.createdAt, d.updatedAt
            order by d.updatedAt desc, q.id desc
            """;

    private final EntityManager entityManager;

    public JpaQuizLibraryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<QuizLibraryItem> findOwned(UUID ownerUserId, QuizLibraryPosition before, int fetchLimit) {
        String hql = SELECT + (before == null ? "" : CURSOR_PREDICATE) + ORDERING;
        TypedQuery<QuizLibraryItem> query = entityManager
                .createQuery(hql, QuizLibraryItem.class)
                .setParameter("ownerUserId", ownerUserId)
                .setMaxResults(fetchLimit);
        if (before != null) {
            query.setParameter("cursorUpdatedAt", before.updatedAt());
            query.setParameter("cursorQuizId", before.quizId());
        }
        return query.getResultList();
    }
}
