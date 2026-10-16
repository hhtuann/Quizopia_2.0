import { canonicalUuid } from "./monitoring";

/**
 * Minimal authoritative Attempt projection expected from the future Attempt UI.
 * This is a frontend integration boundary, not a substitute for the backend's
 * fresh authorization checks.
 */
export interface MonitoringEligibility {
  readonly attemptId: string;
  readonly audience: "CLASS" | "PUBLIC";
  readonly monitoringChoice: "UNCONFIGURED" | "ENABLED" | "DISABLED";
  readonly attemptStatus: "ACTIVE" | "SUBMITTED" | "TIMED_OUT" | "ABANDONED";
  readonly deadlineAt: string | null;
}

export type MonitoringEligibilityDecision =
  | { readonly eligible: true; readonly attemptId: string }
  | {
      readonly eligible: false;
      readonly reason:
        | "missing"
        | "invalid-attempt"
        | "public"
        | "not-enabled"
        | "unbounded"
        | "not-active";
    };

/**
 * Rejects states that are definitively ineligible before any collection occurs.
 * A positive result only permits asking the backend; it never grants authority.
 */
export function evaluateMonitoringEligibility(
  value: MonitoringEligibility | null | undefined,
): MonitoringEligibilityDecision {
  if (value === null || value === undefined)
    return { eligible: false, reason: "missing" };
  if (!canonicalUuid.safeParse(value.attemptId).success)
    return { eligible: false, reason: "invalid-attempt" };
  if (value.audience === "PUBLIC") return { eligible: false, reason: "public" };
  if (value.monitoringChoice !== "ENABLED")
    return { eligible: false, reason: "not-enabled" };
  if (value.deadlineAt === null)
    return { eligible: false, reason: "unbounded" };
  if (value.attemptStatus !== "ACTIVE")
    return { eligible: false, reason: "not-active" };
  return { eligible: true, attemptId: value.attemptId };
}
