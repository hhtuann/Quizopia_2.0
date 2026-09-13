package com.quizopia.classroom.persistence;

import com.quizopia.classroom.domain.*;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "classrooms")
class ClassroomEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_teacher_user_id", nullable = false, updatable = false)
    private UUID ownerTeacherUserId;

    @Column(name = "name", nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "subject", nullable = false, columnDefinition = "text")
    private String subject;

    @Column(name = "grade", nullable = false, columnDefinition = "text")
    private String grade;

    @Column(name = "description", nullable = true, columnDefinition = "text")
    private String description;

    protected ClassroomEntity() {}

    ClassroomEntity(Classroom value) {
        this.id = value.id();
        this.ownerTeacherUserId = value.ownerTeacherUserId();
        this.name = value.name();
        this.subject = value.subject();
        this.grade = value.grade();
        this.description = value.description();
    }

    Classroom toDomain() {
        return new Classroom(id, ownerTeacherUserId, name, subject, grade, description);
    }
}
