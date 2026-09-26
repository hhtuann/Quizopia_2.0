package com.quizopia.classroom.persistence;

import com.quizopia.classroom.domain.*;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "pending_classroom_invitations")
class PendingClassroomInvitationEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "classroom_id", nullable = false, updatable = false)
    private UUID classroomId;

    @Column(name = "normalized_email", nullable = false, length = 320)
    private String normalizedEmail;

    @Column(name = "invited_full_name", nullable = false, columnDefinition = "text")
    private String invitedFullName;

    protected PendingClassroomInvitationEntity() {}

    PendingClassroomInvitationEntity(PendingClassroomInvitation value) {
        this.id = value.id();
        this.classroomId = value.classroomId();
        this.normalizedEmail = value.email().value();
        this.invitedFullName = value.invitedFullName();
    }

    PendingClassroomInvitation toDomain() {
        return new PendingClassroomInvitation(
                id, classroomId, new NormalizedInvitationEmail(normalizedEmail), invitedFullName);
    }
}
