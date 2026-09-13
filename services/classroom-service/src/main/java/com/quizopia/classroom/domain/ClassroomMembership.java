package com.quizopia.classroom.domain;

import java.util.Objects;
import java.util.UUID;

public record ClassroomMembership(UUID id, UUID classroomId, UUID userId) {
    public ClassroomMembership {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(classroomId, "classroomId");
        Objects.requireNonNull(userId, "userId");
    }
}
