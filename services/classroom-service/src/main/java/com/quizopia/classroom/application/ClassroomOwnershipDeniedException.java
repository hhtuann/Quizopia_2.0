package com.quizopia.classroom.application;

import java.util.Objects;
import java.util.UUID;

public final class ClassroomOwnershipDeniedException extends RuntimeException {
    private final UUID classroomId;
    private final UUID callerUserId;

    public ClassroomOwnershipDeniedException(UUID classroomId, UUID callerUserId) {
        super("Caller is not the classroom owner");
        this.classroomId = Objects.requireNonNull(classroomId, "classroomId");
        this.callerUserId = Objects.requireNonNull(callerUserId, "callerUserId");
    }

    public UUID classroomId() {
        return classroomId;
    }

    public UUID callerUserId() {
        return callerUserId;
    }
}
