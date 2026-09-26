package com.quizopia.classroom.api;

import com.quizopia.classroom.domain.Classroom;
import java.util.UUID;

public record ClassroomResponse(
        UUID id, UUID ownerTeacherUserId, String name, String subject, String grade, String description) {
    public static ClassroomResponse from(Classroom classroom) {
        return new ClassroomResponse(
                classroom.id(),
                classroom.ownerTeacherUserId(),
                classroom.name(),
                classroom.subject(),
                classroom.grade(),
                classroom.description());
    }
}
