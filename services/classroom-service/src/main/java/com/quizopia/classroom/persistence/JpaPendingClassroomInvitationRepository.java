package com.quizopia.classroom.persistence;

import com.quizopia.classroom.application.PendingClassroomInvitationRepository;
import com.quizopia.classroom.domain.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaPendingClassroomInvitationRepository implements PendingClassroomInvitationRepository {
    private final EntityManager entityManager;

    public JpaPendingClassroomInvitationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void insert(PendingClassroomInvitation value) {
        entityManager.persist(new PendingClassroomInvitationEntity(value));
        entityManager.flush();
    }

    @Override
    public Optional<PendingClassroomInvitation> findByClassroomIdAndEmail(
            UUID classroomId, NormalizedInvitationEmail email) {
        return entityManager
                .createQuery(
                        "select e from PendingClassroomInvitationEntity e where e.classroomId = :classroomId and e.normalizedEmail = :email",
                        PendingClassroomInvitationEntity.class)
                .setParameter("classroomId", classroomId)
                .setParameter("email", email.value())
                .getResultList()
                .stream()
                .findFirst()
                .map(PendingClassroomInvitationEntity::toDomain);
    }

    @Override
    @Transactional
    public List<PendingClassroomInvitation> findAllByEmailForClaim(NormalizedInvitationEmail email) {
        return entityManager
                .createQuery(
                        "select e from PendingClassroomInvitationEntity e where e.normalizedEmail = :email order by e.id",
                        PendingClassroomInvitationEntity.class)
                .setParameter("email", email.value())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList()
                .stream()
                .map(PendingClassroomInvitationEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void deleteById(UUID id) {
        int deleted = entityManager
                .createQuery("delete from PendingClassroomInvitationEntity e where e.id = :id")
                .setParameter("id", id)
                .executeUpdate();
        if (deleted != 1) {
            throw new IllegalStateException("Pending invitation disappeared during claim: " + id);
        }
    }
}
