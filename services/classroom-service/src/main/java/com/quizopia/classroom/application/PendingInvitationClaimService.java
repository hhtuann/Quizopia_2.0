package com.quizopia.classroom.application;

import com.quizopia.classroom.domain.ClassroomMembership;
import com.quizopia.classroom.domain.NormalizedInvitationEmail;
import com.quizopia.classroom.domain.PendingClassroomInvitation;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims local pending invitations from an already-verified Identity fact.
 *
 * <p>This use case must be invoked only by a trusted integration adapter after an Identity integration contract has
 * been accepted. It does not authenticate browser input or establish a cross-service email canonicalization rule.
 */
@Service
public class PendingInvitationClaimService {
    private final ClassroomMembershipRepository membershipRepository;
    private final PendingClassroomInvitationRepository invitationRepository;
    private final ClassroomRecordIdGenerator idGenerator;

    public PendingInvitationClaimService(
            ClassroomMembershipRepository membershipRepository,
            PendingClassroomInvitationRepository invitationRepository,
            ClassroomRecordIdGenerator idGenerator) {
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "membershipRepository");
        this.invitationRepository = Objects.requireNonNull(invitationRepository, "invitationRepository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Transactional
    public PendingInvitationClaimResult claimPendingInvitationsForVerifiedUser(
            UUID verifiedUserId, String verifiedEmail) {
        Objects.requireNonNull(verifiedUserId, "verifiedUserId");
        NormalizedInvitationEmail email = new NormalizedInvitationEmail(verifiedEmail);
        List<PendingClassroomInvitation> invitations = invitationRepository.findAllByEmailForClaim(email);

        int membershipsCreated = 0;
        for (PendingClassroomInvitation invitation : invitations) {
            if (membershipRepository
                    .findByClassroomIdAndUserId(invitation.classroomId(), verifiedUserId)
                    .isEmpty()) {
                ClassroomMembership membership =
                        new ClassroomMembership(nextId(), invitation.classroomId(), verifiedUserId);
                if (membershipRepository.insertIfAbsent(membership)) {
                    membershipsCreated++;
                }
            }
            invitationRepository.deleteById(invitation.id());
        }

        return new PendingInvitationClaimResult(invitations.size(), membershipsCreated);
    }

    private UUID nextId() {
        return Objects.requireNonNull(idGenerator.generate(), "idGenerator returned null");
    }
}
