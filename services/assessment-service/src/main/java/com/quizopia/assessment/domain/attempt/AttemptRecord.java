package com.quizopia.assessment.domain.attempt;

import java.time.Instant;
import java.util.UUID;

/** Assessment-owned persistence projection; never a browser delivery DTO. */
public record AttemptRecord(
        UUID id,
        UUID publicationId,
        UUID studentId,
        long ordinal,
        UUID snapshotId,
        int gradingPolicyVersion,
        int answerSchemaVersion,
        int durationMinutes,
        Instant startedAt,
        Instant deadlineAt,
        long revision,
        State state,
        Reason terminalReason,
        Instant finalizedAt,
        UUID submittedAnswersId,
        UUID resultId) {
    public enum State {
        ACTIVE,
        SUBMITTED,
        ABANDONED
    }

    public enum Reason {
        MANUAL_SUBMIT,
        BOUNDED_TIMEOUT,
        TEACHER_NOTICE_CUTOFF,
        STUDENT_ABANDON
    }

    @Override
    public String toString() {
        return "AttemptRecord[redacted]";
    }
}
