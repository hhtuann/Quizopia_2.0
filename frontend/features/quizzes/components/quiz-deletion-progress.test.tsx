import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { QuizDeletionOperationStatus } from "../api/quiz-deletion-client";
import {
  DELETION_POLL_INTERVAL_MS,
  QuizDeletionProgress,
} from "./quiz-deletion-progress";

const operationId = "1c9d0d7e-5c8a-4b8f-9a55-1d3a6d3a2a11";

type PollResult =
  | { readonly ok: true; readonly value: QuizDeletionOperationStatus }
  | { readonly ok: false; readonly error: { readonly kind: string } };

function status(
  state: string,
  overrides: Partial<QuizDeletionOperationStatus> = {},
): PollResult {
  return {
    ok: true,
    value: {
      operationId,
      status: state,
      createdAt: "2026-10-20T08:00:00Z",
      updatedAt: "2026-10-20T08:01:00Z",
      lastReason: null,
      stages: [],
      note: null,
      ...overrides,
    },
  };
}

function makeReader(script: ReadonlyArray<PollResult>) {
  const getDeletionStatus = vi.fn(async (): Promise<PollResult> => {
    // mock.calls already contains the in-flight call, hence - 1.
    const index = Math.min(
      getDeletionStatus.mock.calls.length - 1,
      script.length - 1,
    );
    return script[index] ?? status("REQUESTED");
  });
  return { getDeletionStatus };
}

function renderProgress(options: {
  readonly getDeletionStatus: ReturnType<
    typeof makeReader
  >["getDeletionStatus"];
  readonly initialStatus?: string;
  readonly initialNote?: string | null;
  readonly onTerminal?: (status: string) => void;
}) {
  const onTerminal = vi.fn(options.onTerminal);
  render(
    <QuizDeletionProgress
      getDeletionStatus={options.getDeletionStatus}
      initialStatus={options.initialStatus}
      initialNote={options.initialNote}
      onTerminal={onTerminal}
    />,
  );
  return { onTerminal };
}

/** Flush the first immediate poll plus any pending renders. */
async function flushFirstPoll() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

async function advancePoll() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(DELETION_POLL_INTERVAL_MS);
  });
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("QuizDeletionProgress honest step mapping", () => {
  it.each([
    ["REQUESTED", "Revoking access"],
    ["QUIZ_FENCED", "Revoking access"],
    ["DEPENDENCIES_FENCING", "Revoking access"],
    ["ACCESS_REVOKED", "Access blocked · Deleting data"],
    ["PURGE_PENDING", "Access blocked · Deleting data"],
    ["PURGING", "Access blocked · Deleting data"],
    ["BLOCKED_BY_RETENTION", "Waiting for the mandatory retention period"],
    ["PENDING_POLICY", "Waiting for the mandatory retention period"],
    [
      "WAITING_DEPENDENCY",
      "Quizopia is retrying automatically; nothing needs your attention right now.",
    ],
    [
      "RETRYABLE_FAILURE",
      "Quizopia is retrying automatically; nothing needs your attention right now.",
    ],
    ["PRIMARY_PURGED", "Primary data deleted"],
    ["ALL_COPIES_ERASED", "Fully deleted"],
  ] as const)("renders '%s' as %s", async (state, expected) => {
    const reader = makeReader([status(state)]);
    renderProgress({ getDeletionStatus: reader.getDeletionStatus });

    await flushFirstPoll();
    expect(screen.getByText(expected)).toBeInTheDocument();
  });

  it("appends the honest backup-copies note at PRIMARY_PURGED + COPY_EXPIRY_PENDING", async () => {
    const reader = makeReader([
      status("PRIMARY_PURGED", { note: "COPY_EXPIRY_PENDING" }),
    ]);
    renderProgress({ getDeletionStatus: reader.getDeletionStatus });

    await flushFirstPoll();
    expect(
      screen.getByText(
        "Primary data deleted · Waiting for backup copies to be erased",
      ),
    ).toBeInTheDocument();
    // Honesty: the copies-pending state is NOT "fully deleted".
    expect(screen.queryByText("Fully deleted")).not.toBeInTheDocument();
  });

  it("NEVER claims 'Fully deleted' before ALL_COPIES_ERASED", async () => {
    const reader = makeReader([
      status("ACCESS_REVOKED"),
      status("PURGING"),
      status("PRIMARY_PURGED", { note: "COPY_EXPIRY_PENDING" }),
    ]);
    renderProgress({ getDeletionStatus: reader.getDeletionStatus });

    await flushFirstPoll();
    expect(screen.queryByText("Fully deleted")).not.toBeInTheDocument();
    await advancePoll();
    expect(screen.queryByText("Fully deleted")).not.toBeInTheDocument();
    await advancePoll();
    expect(screen.queryByText("Fully deleted")).not.toBeInTheDocument();
  });

  it("shows the server's no-PII lastReason verbatim for retention pauses", async () => {
    const reader = makeReader([
      status("BLOCKED_BY_RETENTION", { lastReason: "retention window active" }),
    ]);
    renderProgress({ getDeletionStatus: reader.getDeletionStatus });

    await flushFirstPoll();
    expect(
      screen.getByText("Latest note: retention window active"),
    ).toBeInTheDocument();
  });
});

describe("QuizDeletionProgress polling lifecycle", () => {
  it("polls on the bounded 3s cadence while the operation is visible", async () => {
    const reader = makeReader([status("REQUESTED"), status("QUIZ_FENCED")]);
    renderProgress({ getDeletionStatus: reader.getDeletionStatus });

    await flushFirstPoll();
    expect(reader.getDeletionStatus).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Revoking access")).toBeInTheDocument();

    await advancePoll();
    expect(reader.getDeletionStatus).toHaveBeenCalledTimes(2);
  });

  it("stops polling and fires onTerminal exactly once at PRIMARY_PURGED", async () => {
    const reader = makeReader([status("PURGING"), status("PRIMARY_PURGED")]);
    const { onTerminal } = renderProgress({
      getDeletionStatus: reader.getDeletionStatus,
    });

    await flushFirstPoll();
    await advancePoll();
    expect(onTerminal).toHaveBeenCalledTimes(1);
    expect(onTerminal).toHaveBeenCalledWith("PRIMARY_PURGED");
    expect(screen.getByText("Primary data deleted")).toBeInTheDocument();

    // No further polls after the terminal state.
    const callsAfterTerminal = reader.getDeletionStatus.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(DELETION_POLL_INTERVAL_MS * 5);
    });
    expect(reader.getDeletionStatus.mock.calls.length).toBe(callsAfterTerminal);
    expect(onTerminal).toHaveBeenCalledTimes(1);
  });

  it("fires onTerminal for ALL_COPIES_ERASED without polling further", async () => {
    const reader = makeReader([status("ALL_COPIES_ERASED")]);
    const { onTerminal } = renderProgress({
      getDeletionStatus: reader.getDeletionStatus,
    });

    await flushFirstPoll();
    expect(onTerminal).toHaveBeenCalledWith("ALL_COPIES_ERASED");
    expect(screen.getByText("Fully deleted")).toBeInTheDocument();
    expect(reader.getDeletionStatus).toHaveBeenCalledTimes(1);
  });

  it("stops automatic polling on a poll error and offers an honest refresh", async () => {
    const reader = makeReader([
      status("REQUESTED"),
      { ok: false, error: { kind: "network" } },
      status("QUIZ_FENCED"),
    ]);
    renderProgress({ getDeletionStatus: reader.getDeletionStatus });

    await flushFirstPoll();
    await advancePoll();
    expect(reader.getDeletionStatus).toHaveBeenCalledTimes(2);
    expect(
      screen.getByText("The deletion status could not be refreshed."),
    ).toBeInTheDocument();
    // The last known honest status is still shown.
    expect(screen.getByText("Revoking access")).toBeInTheDocument();

    const callsAfterFailure = reader.getDeletionStatus.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(DELETION_POLL_INTERVAL_MS * 3);
    });
    expect(reader.getDeletionStatus.mock.calls.length).toBe(callsAfterFailure);

    // Manual refresh restarts the bounded loop.
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Refresh status" }));
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(
      screen.queryByText("The deletion status could not be refreshed."),
    ).not.toBeInTheDocument();
  });
});
