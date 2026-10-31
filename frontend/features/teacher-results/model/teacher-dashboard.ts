import { z } from "zod";
import type {
  TeacherAttemptSummary,
  TeacherMean,
  TeacherScore,
  TeacherScoreBin,
  TeacherStudentHistory,
  TeacherStudentOverview,
  TeacherStudentSummary,
  TeacherStudents,
} from "../../teacher-results/api/teacher-assessment-contract.generated";
import {
  canonicalUuid,
  instantSchema,
} from "../../assessments/model/publication";
import { ordinalSchema, resultSchema } from "./teacher-results";

export type {
  TeacherAttemptSummary,
  TeacherMean,
  TeacherScore,
  TeacherScoreBin,
  TeacherStudentHistory,
  TeacherStudentOverview,
  TeacherStudentSummary,
  TeacherStudents,
} from "../../teacher-results/api/teacher-assessment-contract.generated";

/**
 * Exact decimal-string wire forms for the W4F-T2 aggregate endpoints. Counts
 * and ordinals are canonical nonnegative decimal strings and are rendered
 * verbatim; they never pass through Number, so no precision is lost. The
 * server produced scoreDisplay with its exact HALF_UP presentation rounding.
 */
export const countSchema = z
  .string()
  .regex(/^(0|[1-9][0-9]{0,18})$/)
  .refine(
    (value) => BigInt(value) <= BigInt("9223372036854775807"),
    "Count exceeds the signed 64-bit wire bound.",
  );

const nonnegativeCount = countSchema.refine(
  (value) => value !== "0",
  "Counts of existing facts must be positive.",
);

export const scoreDisplaySchema = z
  .string()
  .regex(/^(10\.00|9\.[0-9]{2}|[0-8]\.[0-9]{2})$/);

export const teacherScoreSchema: z.ZodType<TeacherScore> = z
  .object({
    earnedWeight: z.string().regex(/^(0|[1-9][0-9]*)(\.[0-9]*[1-9])?$/),
    possibleWeight: z.string().regex(/^[1-9][0-9]*$/),
    scoreDisplay: scoreDisplaySchema,
  })
  .strict();

export const teacherMeanSchema: z.ZodType<TeacherMean> = z
  .object({
    sumEarnedWeight: z.string().regex(/^(0|[1-9][0-9]*)(\.[0-9]*[1-9])?$/),
    sumPossibleWeight: z.string().regex(/^[1-9][0-9]*$/),
    gradedAttemptCount: countSchema,
    scoreDisplay: scoreDisplaySchema,
  })
  .strict();

const BOUND_STRINGS = [
  "0",
  "0.5",
  "1",
  "1.5",
  "2",
  "2.5",
  "3",
  "3.5",
  "4",
  "4.5",
  "5",
  "5.5",
  "6",
  "6.5",
  "7",
  "7.5",
  "8",
  "8.5",
  "9",
  "9.5",
  "10",
] as const;

export const teacherScoreBinSchema: z.ZodType<TeacherScoreBin> = z
  .object({
    index: z.number().int().min(0).max(19),
    lowerBound: z.string(),
    upperBound: z.string(),
    lowerInclusive: z.boolean(),
    upperInclusive: z.literal(true),
    count: countSchema,
  })
  .strict()
  .superRefine((bin, context) => {
    if (bin.lowerBound !== BOUND_STRINGS[bin.index]) {
      context.addIssue({
        code: "custom",
        path: ["lowerBound"],
        message: "Bin lower bound must be index/2.",
      });
    }
    if (bin.upperBound !== BOUND_STRINGS[bin.index + 1]) {
      context.addIssue({
        code: "custom",
        path: ["upperBound"],
        message: "Bin upper bound must be (index+1)/2.",
      });
    }
    if (bin.lowerInclusive !== (bin.index === 0)) {
      context.addIssue({
        code: "custom",
        path: ["lowerInclusive"],
        message: "Only bin 0 includes its lower bound.",
      });
    }
  });

export const teacherStudentOverviewSchema: z.ZodType<TeacherStudentOverview> = z
  .object({
    publicationId: canonicalUuid,
    asOf: instantSchema,
    activeStudentCount: countSchema,
    submittedStudentCount: countSchema,
    participatedStudentCount: countSchema,
    gradedStudentCount: countSchema,
    bestScore: teacherScoreSchema.nullable(),
    averageBestScore: teacherMeanSchema.nullable(),
    scoreBins: z.array(teacherScoreBinSchema).length(20),
  })
  .strict()
  .superRefine((overview, context) => {
    const total = overview.scoreBins.reduce(
      (sum, bin) => sum + BigInt(bin.count),
      BigInt(0),
    );
    if (total !== BigInt(overview.gradedStudentCount)) {
      context.addIssue({
        code: "custom",
        path: ["scoreBins"],
        message: "Histogram bin counts must sum to the graded student count.",
      });
    }
    const hasGraded = overview.gradedStudentCount !== "0";
    if (hasGraded !== (overview.bestScore !== null)) {
      context.addIssue({
        code: "custom",
        path: ["bestScore"],
        message:
          "A best score exists exactly when at least one student is graded.",
      });
    }
    if (hasGraded !== (overview.averageBestScore !== null)) {
      context.addIssue({
        code: "custom",
        path: ["averageBestScore"],
        message:
          "An average best score exists exactly when at least one student is graded.",
      });
    }
  });

export const attemptStateSchema = z.enum(["ACTIVE", "SUBMITTED", "ABANDONED"]);

export const teacherAttemptSummarySchema: z.ZodType<TeacherAttemptSummary> = z
  .object({
    attemptId: canonicalUuid,
    ordinal: ordinalSchema,
    state: attemptStateSchema,
    startedAt: instantSchema,
    deadlineAt: instantSchema.nullable(),
    finalizedAt: instantSchema.nullable(),
    terminalReason: z
      .enum([
        "MANUAL_SUBMIT",
        "BOUNDED_TIMEOUT",
        "TEACHER_NOTICE_CUTOFF",
        "STUDENT_ABANDON",
      ])
      .nullable(),
    result: resultSchema.nullable(),
    teacherReviewAvailable: z.boolean(),
  })
  .strict()
  .superRefine((attempt, context) => {
    if (attempt.state !== "SUBMITTED" && attempt.result !== null) {
      context.addIssue({
        code: "custom",
        path: ["result"],
        message: "Only SUBMITTED Attempts can carry a Result.",
      });
    }
    if (
      attempt.terminalReason === "STUDENT_ABANDON" &&
      attempt.result !== null
    ) {
      context.addIssue({
        code: "custom",
        path: ["result"],
        message: "An abandoned Attempt is never graded.",
      });
    }
    if (
      attempt.result !== null &&
      attempt.result.attemptId !== attempt.attemptId
    ) {
      context.addIssue({
        code: "custom",
        path: ["result"],
        message: "Result identity must match its Attempt.",
      });
    }
  });

export const teacherStudentSummarySchema: z.ZodType<TeacherStudentSummary> = z
  .object({
    studentId: canonicalUuid,
    studentName: z.string().min(1).max(255).nullable(),
    attemptCount: nonnegativeCount,
    gradedAttemptCount: countSchema,
    hasActive: z.boolean(),
    hasSubmitted: z.boolean(),
    latestAttempt: teacherAttemptSummarySchema,
    latestGradedResult: resultSchema.nullable(),
    bestResult: resultSchema.nullable(),
    averageResult: teacherMeanSchema.nullable(),
  })
  .strict()
  .superRefine((student, context) => {
    // Cross-field invariants the aggregate SQL guarantees; deliberately
    // defensive shapes (for example an ungraded latest submission racing
    // finalization) stay acceptable rather than failing the whole page.
    if (student.hasActive && student.latestAttempt.state === "ABANDONED") {
      context.addIssue({
        code: "custom",
        path: ["hasActive"],
        message:
          "hasActive must agree with the latest attempt state ordering guarantee.",
      });
    }
    if (
      (student.gradedAttemptCount !== "0") !==
      (student.averageResult !== null)
    ) {
      context.addIssue({
        code: "custom",
        path: ["averageResult"],
        message: "An average exists exactly when the student has graded work.",
      });
    }
    if (
      student.averageResult !== null &&
      student.averageResult.gradedAttemptCount !== student.gradedAttemptCount
    ) {
      context.addIssue({
        code: "custom",
        path: ["averageResult"],
        message: "The average denominator is the student's graded attempts.",
      });
    }
    if (student.attemptCount === "0") {
      context.addIssue({
        code: "custom",
        path: ["attemptCount"],
        message: "Directory rows describe participating students only.",
      });
    }
  });

const opaquePageCursor = z
  .string()
  .min(1)
  .max(2048)
  .regex(/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);

export const teacherStudentsSchema: z.ZodType<TeacherStudents> = z
  .object({
    publicationId: canonicalUuid,
    asOf: instantSchema,
    items: z.array(teacherStudentSummarySchema).max(100),
    nextCursor: opaquePageCursor.nullable(),
  })
  .strict();

export const teacherStudentHistorySchema: z.ZodType<TeacherStudentHistory> = z
  .object({
    publicationId: canonicalUuid,
    studentId: canonicalUuid,
    asOf: instantSchema,
    items: z.array(teacherAttemptSummarySchema).max(100),
    nextCursor: opaquePageCursor.nullable(),
  })
  .strict()
  .superRefine((history, context) => {
    for (let later = 1; later < history.items.length; later += 1) {
      const previous = history.items[later - 1];
      const current = history.items[later];
      if (BigInt(current.ordinal) > BigInt(previous.ordinal)) {
        context.addIssue({
          code: "custom",
          path: ["items"],
          message: "History pages must not ascend between neighbours.",
        });
        break;
      }
    }
  });

export type StudentStatusFilter = "ACTIVE" | "SUBMITTED" | "ABANDONED";
export type StudentSort = "LATEST" | "BEST_SCORE";
export type StudentDirection = "ASC" | "DESC";

export const studentStatusFilterSchema = z.enum([
  "ACTIVE",
  "SUBMITTED",
  "ABANDONED",
]);
export const studentSortSchema = z.enum(["LATEST", "BEST_SCORE"]);
export const studentDirectionSchema = z.enum(["ASC", "DESC"]);
