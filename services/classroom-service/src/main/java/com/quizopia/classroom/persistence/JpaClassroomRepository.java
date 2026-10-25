package com.quizopia.classroom.persistence;

import com.quizopia.classroom.application.ClassroomDirectory;
import com.quizopia.classroom.application.ClassroomDirectoryRepository;
import com.quizopia.classroom.application.ClassroomRepository;
import com.quizopia.classroom.domain.*;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaClassroomRepository implements ClassroomRepository, ClassroomDirectoryRepository {
    private final EntityManager entityManager;

    public JpaClassroomRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void insert(Classroom value) {
        entityManager.persist(new ClassroomEntity(value));
        entityManager.flush();
    }

    @Override
    public Optional<Classroom> findById(UUID id) {
        return Optional.ofNullable(entityManager.find(ClassroomEntity.class, id))
                .map(ClassroomEntity::toDomain);
    }

    @Override
    public java.util.List<ClassroomDirectory.Item> findOwned(
            UUID owner, ClassroomDirectory.Position before, int fetchLimit) {
        String predicate =
                before == null ? "" : " and (c.createdAt < :createdAt or (c.createdAt = :createdAt and c.id < :id))";
        var query = entityManager
                .createQuery(
                        "select new com.quizopia.classroom.application.ClassroomDirectory$Item(c.id,c.name,c.subject,c.grade,c.description,c.createdAt) from ClassroomEntity c where c.ownerTeacherUserId = :owner"
                                + predicate + " order by c.createdAt desc, c.id desc",
                        ClassroomDirectory.Item.class)
                .setParameter("owner", owner)
                .setMaxResults(fetchLimit);
        if (before != null) {
            query.setParameter("createdAt", before.createdAt());
            query.setParameter("id", before.id());
        }
        return query.getResultList();
    }
}
