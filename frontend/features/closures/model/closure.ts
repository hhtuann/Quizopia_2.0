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
    "The closure request is durably recorded. Student notices are prepared and confirmed independently; the Publication is not CLOSED yet.",
  FINALIZING:
    "Closure-driven finalization has started. Due confirmed notices have begun producing system submissions; cancellation can no longer win.",
  CANCELLED:
    "The request was cancelled before any closure-driven finalization. Published notices remain visible to their recipients as non-actionable history.",
  COMPLETED:
    "All Attempts were terminal and all captured members resolved; the owning Teacher's COMPLETE command committed the durable CLOSED transition.",
  RESOLVED_EARLY:
    "The workflow was resolved without its own cutoff path, together with an ordinary all-terminal Publication closure.",
};
