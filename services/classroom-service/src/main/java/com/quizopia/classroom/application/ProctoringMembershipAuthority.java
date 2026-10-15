package com.quizopia.classroom.application;

import java.time.Instant;
import java.util.UUID;

/** Narrow Teacher-authorized target membership projection; conveys no target account authority. */
public interface ProctoringMembershipAuthority {
    Confirmation confirm(UUID teacherId, UUID classroomId, UUID studentId);

    record Confirmation(UUID classroomId, UUID studentId, Instant checkedAt) {
        @Override
        public String toString() {
            return "ProctoringMembershipConfirmation[redacted]";
        }
    }
}
