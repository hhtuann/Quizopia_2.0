import { z } from "zod";
import {
  canonicalUuid,
  instantSchema,
  revisionSchema,
} from "../../assessments/model/publication";
import type {
  ClosureCompletion,
  ClosureView,
} from "../../assessments/api/publication-contract.generated";

export type {
  ClosureCompletion,
  ClosureView,
} from "../../assessments/api/publication-contract.generated";

export const teacherCommandSchema = z
  .object({ expectedRevision: revisionSchema })
  .strict();

export const closureViewSchema = z
  .object({
    closureRequestId: canonicalUuid,
    publicationId: canonicalUuid,
    requestingTeacherId: canonicalUuid,
    requestedAt: instantSchema,
    state: z.enum([
      "PENDING",
      "FINALIZING",
      "CANCELLED",
      "COMPLETED",
      "RESOLVED_EARLY",
    ]),
    finalizationStartedAt: instantSchema.nullable(),
    resolvedAt: instantSchema.nullable(),
    publicationRevision: revisionSchema,
  })
  .strict() satisfies z.ZodType<ClosureView>;

export const closureCompletionSchema = z
  .object({
    closureRequestId: canonicalUuid,
    publicationId: canonicalUuid,
    closed: z.boolean(),
    publicationRevision: revisionSchema,
  })
  .strict() satisfies z.ZodType<ClosureCompletion>;

export type ClosureState = ClosureView["state"];

/** Only PENDING and FINALIZING are live; resolved states never return to live. */
export function isLiveClosureState(state: ClosureState): boolean {
  return state === "PENDING" || state === "FINALIZING";
}

/**
 * Cancellation is allowed only before the first committed closure-driven
 * finalization: PENDING with a null finalizationStartedAt fence.
 */
export function canCancelClosure(view: ClosureView): boolean {
  return view.state === "PENDING" && view.finalizationStartedAt === null;
}

/** COMPLETE is a Teacher command against an exact live request. */
export function canCompleteClosure(view: ClosureView): boolean {
  return isLiveClosureState(view.state);
}

export const closureStateLabels: Record<ClosureState, string> = {
  PENDING: "Pending",
  FINALIZING: "Finalizing",
  CANCELLED: "Cancelled",
  COMPLETED: "Completed",
  RESOLVED_EARLY: "Resolved early",
};

export const closureStateVariants: Record<
  ClosureState,
  "info" | "warning" | "success" | "neutral"
> = {
  PENDING: "info",
  FINALIZING: "warning",
  CANCELLED: "neutral",
  COMPLETED: "success",
  RESOLVED_EARLY: "success",
};

/**
 * Truthful per-state progress descriptions. The backend intentionally
 * discloses no recipient counts, percentages or member lists to the Teacher.
 */
export const closureStateDescriptions: Record<ClosureState, string> = {
  PENDING:
    "The request is recorded. Student notices are prepared and confirmed one by one; the assessment is not CLOSED yet.",
  FINALIZING:
    "Finalization has started. Confirmed due notices now produce system submissions; cancelling is no longer possible.",
  CANCELLED:
    "The request was cancelled before finalization. Students keep their notices as history only.",
  COMPLETED:
    "Every attempt was finished and every included student resolved; your COMPLETE command closed the assessment.",
  RESOLVED_EARLY:
    "The workflow was resolved without its own cutoff path, alongside the normal closure.",
};
