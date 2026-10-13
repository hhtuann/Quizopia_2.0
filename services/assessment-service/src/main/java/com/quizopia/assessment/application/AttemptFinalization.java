package com.quizopia.assessment.application;

import com.quizopia.assessment.domain.attempt.AttemptRecord;
import java.time.Instant;
import java.util.*;

/**
 * Internal root-transaction boundary, NOT an authorization service. Before every call/replay/read,
 * W3-B2 must establish current USER admission, ownership and audience/membership authority. No HTTP
 * adapter or scheduler invokes this core in W3-C1. Call outside an existing database transaction.
 */
public interface AttemptFinalization {
    record SelectedAttempt(UUID attemptId, UUID studentId, Instant expiresAt) {
        public SelectedAttempt(UUID attemptId, UUID studentId) {
            this(attemptId, studentId, null);
        }

        public SelectedAttempt {
            Objects.requireNonNull(attemptId);
            Objects.requireNonNull(studentId);
        }

        public String toString() {
            return "SelectedAttempt[redacted]";
        }
    }

    record Manual(SelectedAttempt selected, long expectedRevision, String keyDigest, String replacementJson) {
        public String toString() {
            return "Manual[redacted]";
        }
    }
    /** Total-only: safe shape for later authorized presentation; no protected answer/question fields. */
    record Result(
            UUID attemptId,
            UUID resultId,
            UUID submittedAnswersId,
            int gradingPolicyVersion,
            long revision,
            AttemptRecord.Reason reason,
            Instant finalizedAt,
            String earnedWeight,
            String possibleWeight,
            String percentageDisplay) {
        public String toString() {
            return "Result[redacted]";
        }
    }

    enum Kind {
        SUBMITTED,
        REPLAY,
        DEADLINE_REACHED,
        NOT_DUE,
        NOT_APPLICABLE,
        ALREADY_TERMINAL
    }
    /** DEADLINE_REACHED carries a COMMITTED timeout; map conflict only after this method returns. */
    record Outcome(Kind kind, Optional<Result> result) {}

    Outcome submit(Manual request);

    Outcome systemFinalize(UUID attemptId, AttemptRecord.Reason reason);

    Optional<Result> result(SelectedAttempt selected);

    Optional<Result> best(UUID publicationId, UUID studentId);
}
