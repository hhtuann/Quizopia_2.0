import { describe, expect, it } from "vitest";
import {
  teacherStudentOverviewSchema,
  teacherStudentsSchema,
  teacherStudentHistorySchema,
  teacherAttemptSummarySchema,
} from "./teacher-dashboard";
import type { TeacherStudentOverview } from "../../teacher-results/api/teacher-assessment-contract.generated";

const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const studentId = "b7b14962-3a2e-4d2d-926a-3b36ea90c196";
const attemptId = "a7b14962-3a2e-4d2d-926a-3b36ea90c197";

const result = {
  resultId: "17b14962-3a2e-4d2d-926a-3b36ea90c193",
  attemptId,
  publicationId,
  ordinal: "1",
  earnedWeight: "8",
  possibleWeight: "10",
  percentageDisplay: "80.00",
  gradingPolicyVersion: 1 as const,
  gradingPolicyId: "QUIZOPIA_GRADING_V1" as const,
  terminalReason: "MANUAL_SUBMIT" as const,
  finalizedAt: "2026-10-30T09:20:00Z",
};

function bins(counts: readonly string[]): TeacherStudentOverview["scoreBins"] {
  return counts.map((count, index) => ({
    index,
    lowerBound: (index / 2).toString(),
    upperBound: ((index + 1) / 2).toString(),
    lowerInclusive: index === 0,
    upperInclusive: true as const,
    count,
  }));
}

const overview = {
  publicationId,
  asOf: "2026-10-30T11:00:00Z",
  activeStudentCount: "0",
  submittedStudentCount: "1",
  participatedStudentCount: "1",
  gradedStudentCount: "1",
  bestScore: {
    earnedWeight: "8",
    possibleWeight: "10",
    scoreDisplay: "8.00",
  },
  averageBestScore: {
    sumEarnedWeight: "8",
    sumPossibleWeight: "10",
    gradedAttemptCount: "1",
    scoreDisplay: "8.00",
  },
  scoreBins: bins(
    Array.from({ length: 20 }, (_, index) => (index === 15 ? "1" : "0")),
  ),
};

describe("teacher dashboard wire contract", () => {
  it("accepts the real aggregate shape with exactly twenty ordered bins", () => {
    const parsed = teacherStudentOverviewSchema.safeParse(overview);
    expect(parsed.success).toBe(true);
    if (parsed.success) expect(parsed.data.scoreBins).toHaveLength(20);
  });

  it("requires the bin counts to sum to the graded student count", () => {
    const shifted = [...overview.scoreBins];
    shifted[15] = { ...shifted[15]!, count: "2" };
    expect(
      teacherStudentOverviewSchema.safeParse({
        ...overview,
        scoreBins: shifted,
      }).success,
    ).toBe(false);
  });

  it("requires bound strings on the exact half-point lattice", () => {
    const corrupted = [...overview.scoreBins];
    corrupted[7] = {
      ...corrupted[7]!,
      lowerBound: "3.49",
      upperBound: "3.99",
    };
    expect(
      teacherStudentOverviewSchema.safeParse({
        ...overview,
        scoreBins: corrupted,
      }).success,
    ).toBe(false);
  });

  it("rejects nineteen bins and a best score without graded students", () => {
    expect(
      teacherStudentOverviewSchema.safeParse({
        ...overview,
        scoreBins: overview.scoreBins.slice(1),
      }).success,
    ).toBe(false);
    expect(
      teacherStudentOverviewSchema.safeParse({
        ...overview,
        gradedStudentCount: "0",
        scoreBins: bins(Array.from({ length: 20 }, () => "0")),
      }).success,
    ).toBe(false);
  });

  it("keeps scores as exact two-decimal strings out of ten", () => {
    expect(
      teacherStudentOverviewSchema.safeParse({
        ...overview,
        bestScore: {
          earnedWeight: "10",
          possibleWeight: "10",
          scoreDisplay: "10.0",
        },
      }).success,
    ).toBe(false);
  });

  it("accepts a directory page and requires the segmented opaque cursor", () => {
    const page = {
      publicationId,
      asOf: "2026-10-30T11:00:00Z",
      items: [
        {
          studentId,
          studentName: "Nguyễn Văn An",
          attemptCount: "1",
          gradedAttemptCount: "1",
          hasActive: false,
          hasSubmitted: true,
          latestAttempt: {
            attemptId,
            ordinal: "1",
            state: "SUBMITTED",
            startedAt: "2026-10-30T09:00:00Z",
            deadlineAt: null,
            finalizedAt: "2026-10-30T09:20:00Z",
            terminalReason: "MANUAL_SUBMIT",
            result,
            teacherReviewAvailable: true,
          },
          latestGradedResult: result,
          bestResult: result,
          averageResult: {
            sumEarnedWeight: "8",
            sumPossibleWeight: "10",
            gradedAttemptCount: "1",
            scoreDisplay: "8.00",
          },
        },
      ],
      nextCursor: "segment.signature",
    };
    expect(teacherStudentsSchema.safeParse(page).success).toBe(true);
    expect(
      teacherStudentsSchema.safeParse({
        ...page,
        nextCursor: "spaces in cursor",
      }).success,
    ).toBe(false);
    expect(
      teacherStudentsSchema.safeParse({
        ...page,
        items: [{ ...page.items[0]!, attemptCount: "0" }],
      }).success,
    ).toBe(false);
  });

  it("rejects a non-participating directory row and average mismatches", () => {
    const row = {
      studentId,
      studentName: null,
      attemptCount: "2",
      gradedAttemptCount: "1",
      hasActive: false,
      hasSubmitted: true,
      latestAttempt: {
        attemptId,
        ordinal: "2",
        state: "SUBMITTED",
        startedAt: "2026-10-30T09:00:00Z",
        deadlineAt: null,
        finalizedAt: "2026-10-30T09:20:00Z",
        terminalReason: "MANUAL_SUBMIT",
        result,
        teacherReviewAvailable: true,
      },
      latestGradedResult: result,
      bestResult: result,
      averageResult: {
        sumEarnedWeight: "8",
        sumPossibleWeight: "10",
        gradedAttemptCount: "3",
        scoreDisplay: "8.00",
      },
    };
    expect(
      teacherStudentsSchema.safeParse({
        publicationId,
        asOf: "2026-10-30T11:00:00Z",
        items: [row],
        nextCursor: null,
      }).success,
    ).toBe(false);
  });

  it("rejects history pages that ascend between neighbours", () => {
    const attempt = (ordinal: string) => ({
      attemptId:
        ordinal === "1" ? attemptId : "d7b14962-3a2e-4d2d-926a-3b36ea90c191",
      ordinal,
      state: "ABANDONED" as const,
      startedAt: "2026-10-30T10:00:00Z",
      deadlineAt: null,
      finalizedAt: "2026-10-30T10:05:00Z",
      terminalReason: "STUDENT_ABANDON" as const,
      result: null,
      teacherReviewAvailable: false,
    });
    expect(
      teacherStudentHistorySchema.safeParse({
        publicationId,
        studentId,
        asOf: "2026-10-30T11:00:00Z",
        items: [attempt("2"), attempt("1")],
        nextCursor: null,
      }).success,
    ).toBe(true);
    expect(
      teacherStudentHistorySchema.safeParse({
        publicationId,
        studentId,
        asOf: "2026-10-30T11:00:00Z",
        items: [attempt("1"), attempt("2")],
        nextCursor: null,
      }).success,
    ).toBe(false);
  });

  it("rejects graded ABANDONED attempts and unknown fields", () => {
    const abandoned = {
      attemptId,
      ordinal: "1",
      state: "ABANDONED",
      startedAt: "2026-10-30T10:00:00Z",
      deadlineAt: null,
      finalizedAt: "2026-10-30T10:05:00Z",
      terminalReason: "STUDENT_ABANDON",
      result: null,
      teacherReviewAvailable: false,
    };
    expect(teacherAttemptSummarySchema.safeParse(abandoned).success).toBe(true);
    expect(
      teacherAttemptSummarySchema.safeParse({
        ...abandoned,
        result,
      }).success,
    ).toBe(false);
    expect(
      teacherAttemptSummarySchema.safeParse({
        ...abandoned,
        correctAnswer: "A",
      }).success,
    ).toBe(false);
  });
});
