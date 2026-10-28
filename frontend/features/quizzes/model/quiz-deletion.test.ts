import { describe, expect, it } from "vitest";
import {
  deletionProgressStep,
  isDeletionFenced,
  isDeletionStatusTerminal,
} from "./quiz-deletion";

describe("deletionProgressStep", () => {
  it("maps every backend main status to its honest progress step", () => {
    expect(deletionProgressStep("REQUESTED")).toBe("revoking-access");
    expect(deletionProgressStep("QUIZ_FENCED")).toBe("revoking-access");
    expect(deletionProgressStep("DEPENDENCIES_FENCING")).toBe(
      "revoking-access",
    );
    expect(deletionProgressStep("ACCESS_REVOKED")).toBe("deleting-data");
    expect(deletionProgressStep("PURGE_PENDING")).toBe("deleting-data");
    expect(deletionProgressStep("PURGING")).toBe("deleting-data");
    expect(deletionProgressStep("BLOCKED_BY_RETENTION")).toBe(
      "waiting-retention",
    );
    expect(deletionProgressStep("PENDING_POLICY")).toBe("waiting-retention");
    expect(deletionProgressStep("WAITING_DEPENDENCY")).toBe("retrying");
    expect(deletionProgressStep("RETRYABLE_FAILURE")).toBe("retrying");
    expect(deletionProgressStep("ALL_COPIES_ERASED")).toBe("all-copies-erased");
  });

  it("shows the copies-pending note exactly at PRIMARY_PURGED with COPY_EXPIRY_PENDING", () => {
    expect(deletionProgressStep("PRIMARY_PURGED")).toBe("primary-deleted");
    expect(deletionProgressStep("PRIMARY_PURGED", "COPY_EXPIRY_PENDING")).toBe(
      "primary-deleted-copies-pending",
    );
    expect(deletionProgressStep("PRIMARY_PURGED", null)).toBe(
      "primary-deleted",
    );
    // The note never upgrades another status to a copies claim.
    expect(deletionProgressStep("PURGING", "COPY_EXPIRY_PENDING")).toBe(
      "deleting-data",
    );
  });

  it("NEVER reports the fully-deleted step outside ALL_COPIES_ERASED", () => {
    const everyStatus = [
      "REQUESTED",
      "QUIZ_FENCED",
      "DEPENDENCIES_FENCING",
      "ACCESS_REVOKED",
      "PURGE_PENDING",
      "PURGING",
      "PRIMARY_PURGED",
      "WAITING_DEPENDENCY",
      "BLOCKED_BY_RETENTION",
      "PENDING_POLICY",
      "RETRYABLE_FAILURE",
      // Unknown future statuses must also never claim completion.
      "SOME_FUTURE_STATUS",
    ] as const;
    for (const status of everyStatus) {
      for (const note of [undefined, null, "COPY_EXPIRY_PENDING"]) {
        expect(deletionProgressStep(status, note)).not.toBe(
          "all-copies-erased",
        );
      }
    }
  });

  it("falls back to the safe in-progress step for unknown future statuses", () => {
    expect(deletionProgressStep("SOME_FUTURE_STATUS")).toBe("revoking-access");
  });
});

describe("isDeletionStatusTerminal", () => {
  it("treats PRIMARY_PURGED and ALL_COPIES_ERASED as polling-terminal", () => {
    expect(isDeletionStatusTerminal("PRIMARY_PURGED")).toBe(true);
    expect(isDeletionStatusTerminal("ALL_COPIES_ERASED")).toBe(true);
    for (const status of [
      "REQUESTED",
      "QUIZ_FENCED",
      "DEPENDENCIES_FENCING",
      "ACCESS_REVOKED",
      "PURGE_PENDING",
      "PURGING",
      "WAITING_DEPENDENCY",
      "BLOCKED_BY_RETENTION",
      "PENDING_POLICY",
      "RETRYABLE_FAILURE",
    ]) {
      expect(isDeletionStatusTerminal(status)).toBe(false);
    }
  });
});

describe("isDeletionFenced", () => {
  it("fences exactly when a deletion status is present", () => {
    expect(isDeletionFenced(null)).toBe(false);
    expect(isDeletionFenced("REQUESTED")).toBe(true);
    expect(isDeletionFenced("BLOCKED_BY_RETENTION")).toBe(true);
    expect(isDeletionFenced("ALL_COPIES_ERASED")).toBe(true);
  });
});
