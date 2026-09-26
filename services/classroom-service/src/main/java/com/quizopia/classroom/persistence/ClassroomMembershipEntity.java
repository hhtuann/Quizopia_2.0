package com.quizopia.classroom.persistence;

import com.quizopia.classroom.domain.*;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "classroom_memberships")
class ClassroomMembershipEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "classroom_id", nullable = false, updatable = false)
    private UUID classroomId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    protected ClassroomMembershipEntity() {}

    ClassroomMembershipEntity(ClassroomMembership value) {
        this.id = value.id();
        this.classroomId = value.classroomId();
        this.userId = value.userId();
    }

    ClassroomMembership toDomain() {
        return new ClassroomMembership(id, classroomId, userId);
    }
}
