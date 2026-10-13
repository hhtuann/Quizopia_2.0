package com.quizopia.assessment.domain.attempt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Exact persisted totals, with no authoritative rounded percentage or grading algorithm. */
public record StoredResult(
        UUID id,
        UUID attemptId,
        UUID submittedAnswersId,
        UUID snapshotId,
        int gradingPolicyVersion,
        BigDecimal earnedWeight,
        BigDecimal possibleWeight,
        Instant gradedAt) {
    @Override
    public String toString() {
        return "StoredResult[redacted]";
    }
}
