package com.quizopia.classroom.domain;

import java.util.Objects;
import java.util.UUID;

public record Classroom(
        UUID id, UUID ownerTeacherUserId, String name, String subject, String grade, String description) {
    public Classroom {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerTeacherUserId, "ownerTeacherUserId");
        name = RequiredText.require(name, "name");
        subject = RequiredText.require(subject, "subject");
        grade = RequiredText.require(grade, "grade");
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerTeacherUserId.equals(Objects.requireNonNull(userId, "userId"));
    }
}
