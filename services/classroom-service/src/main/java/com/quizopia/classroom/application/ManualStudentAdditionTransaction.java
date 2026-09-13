package com.quizopia.classroom.application;

import com.quizopia.classroom.domain.ClassroomMembership;
import com.quizopia.classroom.domain.NormalizedInvitationEmail;
import com.quizopia.classroom.domain.PendingClassroomInvitation;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManualStudentAdditionTransaction {
    private final ClassroomApplicationService classroomApplicationService;
    private final ClassroomMembershipRepository membershipRepository;
    private final PendingClassroomInvitationRepository invitationRepository;
    private final ClassroomRecordIdGenerator idGenerator;

    public ManualStudentAdditionTransaction(
            ClassroomApplicationService classroomApplicationService,
            ClassroomMembershipRepository membershipRepository,
            PendingClassroomInvitationRepository invitationRepository,
            ClassroomRecordIdGenerator idGenerator) {
        this.classroomApplicationService =
                Objects.requireNonNull(classroomApplicationService, "classroomApplicationService");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "membershipRepository");
        this.invitationRepository = Objects.requireNonNull(invitationRepository, "invitationRepository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Transactional
    public ManualStudentAdditionResult establishMembership(
            UUID authenticatedCallerUserId, UUID classroomId, UUID verifiedUserId) {
        Objects.requireNonNull(verifiedUserId, "verifiedUserId");
        classroomApplicationService.getOwnedClassroom(authenticatedCallerUserId, classroomId);

        ClassroomMembership membership = new ClassroomMembership(nextId(), classroomId, verifiedUserId);
        membershipRepository.insert(membership);
        return ManualStudentAdditionResult.membershipEstablished(membership.id());
    }

    @Transactional
    public ManualStudentAdditionResult createPendingInvitation(
            UUID authenticatedCallerUserId, UUID classroomId, NormalizedInvitationEmail email, String invitedFullName) {
        Objects.requireNonNull(email, "email");
        classroomApplicationService.getOwnedClassroom(authenticatedCallerUserId, classroomId);

        PendingClassroomInvitation invitation =
                new PendingClassroomInvitation(nextId(), classroomId, email, invitedFullName);
        invitationRepository.insert(invitation);
        return ManualStudentAdditionResult.pendingInvitationCreated(invitation.id());
    }

    private UUID nextId() {
        return Objects.requireNonNull(idGenerator.generate(), "idGenerator returned null");
    }
}
