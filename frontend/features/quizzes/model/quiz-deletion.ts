import type { QuizDeletionStatus } from "../api/quiz-api-client";

/**
 * Honest presentation mapping for quiz deletion operations (FB-015).
 *
 * Rules enforced here and regression-tested:
 * - "fully deleted" copy is reachable ONLY from ALL_COPIES_ERASED;
 * - PRIMARY_PURGED shows the primary-data-deleted step, plus the
 *   copies-pending note when the backend reports COPY_EXPIRY_PENDING;
 * - retention pauses show the mandatory-retention copy with the server's
 *   lastReason (which contains no PII) when present.
 */

export type DeletionProgressStep =
  | "revoking-access"
  | "deleting-data"
  | "waiting-retention"
  | "retrying"
  | "primary-deleted"
  | "primary-deleted-copies-pending"
  | "all-copies-erased";

export const COPIES_PENDING_NOTE_TOKEN = "COPY_EXPIRY_PENDING";

export function deletionProgressStep(
  status: QuizDeletionStatus | string,
  note?: string | null,
): DeletionProgressStep {
  switch (status) {
    case "REQUESTED":
    case "QUIZ_FENCED":
    case "DEPENDENCIES_FENCING":
      return "revoking-access";
    case "ACCESS_REVOKED":
    case "PURGE_PENDING":
    case "PURGING":
      return "deleting-data";
    case "BLOCKED_BY_RETENTION":
    case "PENDING_POLICY":
      return "waiting-retention";
    case "WAITING_DEPENDENCY":
    case "RETRYABLE_FAILURE":
      return "retrying";
    case "PRIMARY_PURGED":
      return note === COPIES_PENDING_NOTE_TOKEN
        ? "primary-deleted-copies-pending"
        : "primary-deleted";
    case "ALL_COPIES_ERASED":
      return "all-copies-erased";
    default:
      // Unknown future status: never claim completion; show the safe
      // in-progress step rather than a fabricated terminal state.
      return "revoking-access";
  }
}

/**
 * The poller stops when polling can no longer change the presentation:
 * PRIMARY_PURGED is this PR's effective terminal boundary (the backend
 * documents ALL_COPIES_ERASED as unreachable), and ALL_COPIES_ERASED is the
 * irreversible terminal state whenever it appears.
 */
export function isDeletionStatusTerminal(
  status: QuizDeletionStatus | string,
): boolean {
  return status === "PRIMARY_PURGED" || status === "ALL_COPIES_ERASED";
}

/** statuses that mean the quiz is fenced against editing/publishing. */
export function isDeletionFenced(
  deletionStatus: QuizDeletionStatus | null,
): boolean {
  return deletionStatus !== null;
}
