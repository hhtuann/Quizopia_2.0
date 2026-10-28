import { z } from "zod";
import type {
  Result,
  TeacherResultRow,
  TeacherResults as TeacherResultsContract,
} from "../../assessments/api/publication-contract.generated";
import {
  canonicalUuid,
  instantSchema,
} from "../../assessments/model/publication";

export type {
  Result,
  TeacherResultRow,
} from "../../assessments/api/publication-contract.generated";
export type { TeacherResults } from "../../assessments/api/publication-contract.generated";

/**
 * Exact decimal-string wire forms. Ordinals, earned and possible weights are
 * displayed verbatim; they are never routed through Number so no precision
 * can be lost. The server already produced percentageDisplay with its exact
 * HALF_UP scale-2 rounding.
 */
export const ordinalSchema = z
  .string()
  .regex(/^[1-9][0-9]{0,18}$/)
  .refine(
    (value) =>
      !/^[1-9][0-9]{0,18}$/.test(value) ||
      BigInt(value) <= BigInt("9223372036854775807"),
    "Ordinal exceeds the signed 64-bit wire bound.",
  );
export const earnedWeightSchema = z
  .string()
  .regex(/^(0|[1-9][0-9]*)(\.[0-9]*[1-9])?$/);
export const possibleWeightSchema = z.string().regex(/^[1-9][0-9]*$/);
export const percentageDisplaySchema = z
  .string()
  .regex(/^(100\.00|(?:[0-9]|[1-9][0-9])\.[0-9]{2})$/);

export const resultSchema = z
  .object({
    resultId: canonicalUuid,
    attemptId: canonicalUuid,
    publicationId: canonicalUuid,
    ordinal: ordinalSchema,
    earnedWeight: earnedWeightSchema,
    possibleWeight: possibleWeightSchema,
    percentageDisplay: percentageDisplaySchema,
    gradingPolicyVersion: z.literal(1),
    gradingPolicyId: z.literal("QUIZOPIA_GRADING_V1"),
    terminalReason: z.enum([
      "MANUAL_SUBMIT",
      "BOUNDED_TIMEOUT",
      "TEACHER_NOTICE_CUTOFF",
    ]),
    finalizedAt: instantSchema,
  })
  .strict() satisfies z.ZodType<Result>;

export const teacherResultRowSchema = z
  .object({
    attemptId: canonicalUuid,
    studentId: canonicalUuid,
    ordinal: ordinalSchema,
    state: z.enum(["ACTIVE", "SUBMITTED", "ABANDONED"]),
    studentName: z.string().min(1).max(255).nullish(),
    result: resultSchema.nullable(),
  })
  .strict()
  .superRefine((row, context) => {
    if (
      row.result !== null &&
      (row.result.attemptId !== row.attemptId ||
        row.result.ordinal !== row.ordinal)
    ) {
      context.addIssue({
        code: "custom",
        path: ["result"],
        message: "Result identity must match its Attempt row.",
      });
    }
    if (row.state !== "SUBMITTED" && row.result !== null) {
      context.addIssue({
        code: "custom",
        path: ["result"],
        message: "ACTIVE and ABANDONED Attempt rows cannot contain a Result.",
      });
    }
  }) satisfies z.ZodType<TeacherResultRow>;

export const teacherResultsSchema = z
  .object({
    items: z.array(teacherResultRowSchema).max(100),
    nextCursor: z.string().min(1).max(1024).nullable(),
  })
  .strict() satisfies z.ZodType<TeacherResultsContract>;

export const attemptStateLabels: Record<TeacherResultRow["state"], string> = {
  ACTIVE: "Active",
  SUBMITTED: "Submitted",
  ABANDONED: "Abandoned",
};

export const attemptStateVariants: Record<
  TeacherResultRow["state"],
  "info" | "success" | "neutral"
> = {
  ACTIVE: "info",
  SUBMITTED: "success",
  ABANDONED: "neutral",
};

/**
 * Exact totals: earned/possible are rendered as their original decimal
 * strings, never converted to a floating-point number.
 */
export function formatWeightTotal(row: TeacherResultRow): string | null {
  if (row.result === null) return null;
  return `${row.result.earnedWeight} / ${row.result.possibleWeight}`;
}

export const terminalReasonLabels: Record<Result["terminalReason"], string> = {
  MANUAL_SUBMIT: "Manual submission",
  BOUNDED_TIMEOUT: "Bounded timeout",
  TEACHER_NOTICE_CUTOFF: "Teacher notice cutoff",
};

/**
 * Feature-level navigation hook reserved for the Proctoring monitoring
 * composition (Agent 3). The real authorized attemptId is retained on every
 * row; the destination must exist and revalidate access before this helper
 * backs an active link. No monitoring roster endpoint is fabricated here.
 */
export function monitoringHrefForAttempt(attemptId: string): string {
  return `/app/monitoring/teacher/attempts/${attemptId}`;
}
