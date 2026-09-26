package com.quizopia.classroom.domain;

import java.util.Objects;
import java.util.UUID;

public record PendingClassroomInvitation(
        UUID id, UUID classroomId, NormalizedInvitationEmail email, String invitedFullName) {
    public PendingClassroomInvitation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(classroomId, "classroomId");
        Objects.requireNonNull(email, "email");
        invitedFullName = RequiredText.require(invitedFullName, "invitedFullName");
    }

    @Override
    public String toString() {
        return "PendingClassroomInvitation[id=" + id + ", classroomId=" + classroomId + "]";
    }
}
