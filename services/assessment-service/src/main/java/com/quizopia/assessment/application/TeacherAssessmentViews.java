package com.quizopia.assessment.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Explicit teacher-only projections. Directory/statistics never contain answers or keys. */
public final class TeacherAssessmentViews {
    private TeacherAssessmentViews() {}

    public record Score(String earnedWeight, String possibleWeight, String scoreDisplay) {}

    public record Mean(
            String sumEarnedWeight, String sumPossibleWeight, String gradedAttemptCount, String scoreDisplay) {}

    public record Bin(
            int index,
            String lowerBound,
            String upperBound,
            boolean lowerInclusive,
            boolean upperInclusive,
            String count) {}

    public record Overview(
            UUID publicationId,
            Instant asOf,
            String activeStudentCount,
            String submittedStudentCount,
            String participatedStudentCount,
            String gradedStudentCount,
            Score bestScore,
            Mean averageBestScore,
            List<Bin> scoreBins) {}

    public record Attempt(
            UUID attemptId,
            String ordinal,
            String state,
            Instant startedAt,
            Instant deadlineAt,
            Instant finalizedAt,
            String terminalReason,
            AttemptViews.Result result,
            boolean teacherReviewAvailable) {}

    public record Student(
            UUID studentId,
            String studentName,
            String attemptCount,
            String gradedAttemptCount,
            boolean hasActive,
            boolean hasSubmitted,
            Attempt latestAttempt,
            AttemptViews.Result latestGradedResult,
            AttemptViews.Result bestResult,
            Mean averageResult) {}

    public record Students(UUID publicationId, Instant asOf, List<Student> items, String nextCursor) {}

    public record History(UUID publicationId, UUID studentId, Instant asOf, List<Attempt> items, String nextCursor) {}

    public record Review(
            UUID publicationId,
            UUID attemptId,
            UUID snapshotId,
            String state,
            String revision,
            int gradingPolicyVersion,
            Instant startedAt,
            Instant finalizedAt,
            AttemptViews.Result result,
            List<AttemptViews.QuestionReview> questions) {
        public String toString() {
            return "TeacherReview[redacted]";
        }
    }
}
