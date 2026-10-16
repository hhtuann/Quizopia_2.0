package com.quizopia.assessment.application;

import java.time.Instant;
import java.util.UUID;

/** Assessment-owned minimal authority projection; no answers, snapshots or grades. */
public interface ProctoringAuthorityStore {
    Projection find(UUID attemptId);

    record Projection(
            UUID attemptId,
            UUID publicationId,
            UUID classroomId,
            UUID studentId,
            UUID ownerId,
            boolean monitorable,
            boolean active,
            int durationMinutes,
            Instant startedAt,
            Instant deadlineAt,
            Instant checkedAt) {
        @Override
        public String toString() {
            return "ProctoringAuthorityProjection[redacted]";
        }
    }
}
