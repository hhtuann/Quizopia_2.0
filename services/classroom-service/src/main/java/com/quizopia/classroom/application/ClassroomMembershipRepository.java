package com.quizopia.classroom.application;

import com.quizopia.classroom.domain.*;
import java.util.Optional;
import java.util.UUID;

/** Classroom-owned persistence boundary for strict and idempotent membership creation. */
public interface ClassroomMembershipRepository {
    void insert(ClassroomMembership value);

    /** Returns whether this call inserted the membership; an existing classroom/user pair is a successful no-op. */
    boolean insertIfAbsent(ClassroomMembership value);

    Optional<ClassroomMembership> findByClassroomIdAndUserId(UUID classroomId, UUID userId);
}
