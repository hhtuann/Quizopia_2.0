package com.quizopia.classroom.application;

import com.quizopia.classroom.domain.NormalizedInvitationEmail;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates manual student addition without holding a database transaction
 * across user lookup.
 */
public final class ManualStudentAdditionService {
    private final ClassroomApplicationService classroomApplicationService;
    private final VerifiedUserLookup verifiedUserLookup;
    private final ManualStudentAdditionTransaction transaction;

    public ManualStudentAdditionService(
            ClassroomApplicationService classroomApplicationService,
            VerifiedUserLookup verifiedUserLookup,
            ManualStudentAdditionTransaction transaction) {
        this.classroomApplicationService =
                Objects.requireNonNull(classroomApplicationService, "classroomApplicationService");
        this.verifiedUserLookup = Objects.requireNonNull(verifiedUserLookup, "verifiedUserLookup");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
    }

    public ManualStudentAdditionResult addStudent(
            UUID authenticatedCallerUserId, UUID classroomId, ManualStudentAdditionInput input) {
        Objects.requireNonNull(authenticatedCallerUserId, "authenticatedCallerUserId");
        Objects.requireNonNull(classroomId, "classroomId");
        Objects.requireNonNull(input, "input");

        NormalizedInvitationEmail invitationEmail = new NormalizedInvitationEmail(input.email());
        classroomApplicationService.getOwnedClassroom(authenticatedCallerUserId, classroomId);

        Optional<UUID> verifiedUserId = Objects.requireNonNull(
                verifiedUserLookup.findVerifiedUserIdByEmail(input.email()), "verifiedUserLookup returned null");
        return verifiedUserId
                .map(userId -> transaction.establishMembership(authenticatedCallerUserId, classroomId, userId))
                .orElseGet(() -> transaction.createPendingInvitation(
                        authenticatedCallerUserId, classroomId, invitationEmail, input.invitedFullName()));
    }
}
