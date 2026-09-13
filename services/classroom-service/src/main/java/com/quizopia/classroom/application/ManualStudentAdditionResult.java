package com.quizopia.classroom.application;

import java.util.Objects;
import java.util.UUID;

public record ManualStudentAdditionResult(Outcome outcome, UUID createdRecordId) {
    public ManualStudentAdditionResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(createdRecordId, "createdRecordId");
    }

    public static ManualStudentAdditionResult membershipEstablished(UUID membershipId) {
        return new ManualStudentAdditionResult(Outcome.MEMBERSHIP_ESTABLISHED, membershipId);
    }

    public static ManualStudentAdditionResult pendingInvitationCreated(UUID invitationId) {
        return new ManualStudentAdditionResult(Outcome.PENDING_INVITATION_CREATED, invitationId);
    }

    public enum Outcome {
        MEMBERSHIP_ESTABLISHED,
        PENDING_INVITATION_CREATED
    }
}
