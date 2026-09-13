package com.quizopia.classroom.persistence;

import com.quizopia.classroom.application.ClassroomRepository;
import com.quizopia.classroom.domain.*;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaClassroomRepository implements ClassroomRepository {
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
}
