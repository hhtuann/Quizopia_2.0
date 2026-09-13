package com.quizopia.classroom.persistence;

import com.quizopia.classroom.application.ClassroomMembershipRepository;
import com.quizopia.classroom.domain.*;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaClassroomMembershipRepository implements ClassroomMembershipRepository {
    private final EntityManager entityManager;

    public JpaClassroomMembershipRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void insert(ClassroomMembership value) {
        entityManager.persist(new ClassroomMembershipEntity(value));
        entityManager.flush();
    }

    @Override
    @Transactional
    public boolean insertIfAbsent(ClassroomMembership value) {
        int inserted = entityManager
                .createNativeQuery("insert into classroom_memberships (id, classroom_id, user_id) "
                        + "values (:id, :classroomId, :userId) "
                        + "on conflict (classroom_id, user_id) do nothing")
                .setParameter("id", value.id())
                .setParameter("classroomId", value.classroomId())
                .setParameter("userId", value.userId())
                .executeUpdate();
        return inserted == 1;
    }

    @Override
    public Optional<ClassroomMembership> findByClassroomIdAndUserId(UUID classroomId, UUID userId) {
        return entityManager
                .createQuery(
                        "select e from ClassroomMembershipEntity e where e.classroomId = :classroomId and e.userId = :userId",
                        ClassroomMembershipEntity.class)
                .setParameter("classroomId", classroomId)
                .setParameter("userId", userId)
                .getResultList()
                .stream()
                .findFirst()
                .map(ClassroomMembershipEntity::toDomain);
    }
}
