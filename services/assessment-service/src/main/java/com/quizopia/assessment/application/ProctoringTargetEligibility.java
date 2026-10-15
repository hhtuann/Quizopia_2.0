package com.quizopia.assessment.application;

import java.time.Instant;
import java.util.UUID;

/** Purpose-bound target checks; Teacher evidence authenticates only the initiating Teacher. */
public interface ProctoringTargetEligibility {
    void require(UUID classroomId, UUID studentId, Instant attemptStartedAt, String teacherEvidence);
}
