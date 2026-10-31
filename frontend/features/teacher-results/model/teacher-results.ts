import { z } from "zod";
import type { Result } from "../../assessments/api/publication-contract.generated";
import {
  canonicalUuid,
  instantSchema,
} from "../../assessments/model/publication";

export type { Result } from "../../assessments/api/publication-contract.generated";

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

export type AttemptState = "ACTIVE" | "SUBMITTED" | "ABANDONED";

export const attemptStateLabels: Record<AttemptState, string> = {
  ACTIVE: "Active",
  SUBMITTED: "Submitted",
  ABANDONED: "Abandoned",
};

export const attemptStateVariants: Record<
  AttemptState,
  "info" | "success" | "neutral"
> = {
  ACTIVE: "info",
  SUBMITTED: "success",
  ABANDONED: "neutral",
};

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
