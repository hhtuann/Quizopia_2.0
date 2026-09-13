package com.quizopia.classroom.application;

import java.util.Objects;
import java.util.UUID;

public final class ClassroomNotFoundException extends RuntimeException {
    private final UUID classroomId;

    public ClassroomNotFoundException(UUID classroomId) {
        super("Classroom was not found");
        this.classroomId = Objects.requireNonNull(classroomId, "classroomId");
    }

    public UUID classroomId() {
        return classroomId;
    }
}
