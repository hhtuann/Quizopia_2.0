import { act, renderHook } from "@testing-library/react";
import { StrictMode, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { AttemptView } from "../api/attempt-contract.generated";
import type { AttemptApiClient } from "../api/attempt-api-client";
import { useAttemptCoordinator } from "./use-attempt-coordinator";

const attempt: AttemptView = {
  attemptId: "e7b14962-3a2e-4d2d-926a-3b36ea90c199",
  publicationId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  ordinal: "1",
  state: "ACTIVE",
  startedAt: "2026-10-16T08:00:00Z",
  deadlineAt: "2026-10-16T08:40:00Z",
  durationMinutes: 40,
  revision: "0",
  serverNow: "2026-10-16T08:10:00Z",
  questions: [
    {
      number: 1,
      type: "SINGLE_CHOICE",
      stemMarkdown: "Choose",
      options: [
        { label: "A", markdown: "Alpha" },
        { label: "B", markdown: "Beta" },
      ],
    },
  ],
  answers: [
    { questionNumber: 1, answer: { type: "SINGLE_CHOICE", optionLabel: null } },
  ],
  closure: null,
};

function client(): AttemptApiClient {
  return {
    enterPublic: vi.fn(),
    enterClass: vi.fn(),
    start: vi.fn(),
    retake: vi.fn(),
    getAttempt: vi.fn(),
    save: vi.fn(),
    submit: vi.fn(),
    abandon: vi.fn(),
    getResult: vi.fn(),
    getAggregate: vi.fn(),
    getReview: vi.fn(),
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

afterEach(() => {
  vi.useRealTimers();
});

describe("Attempt revision-safe coordinator", () => {
  it("continues autosaving after the React Strict Mode effect replay", async () => {
    vi.useFakeTimers();
    const api = client();
    vi.mocked(api.save).mockResolvedValue({
      ok: true,
      value: {
        attemptId: attempt.attemptId,
        questionNumber: 1,
        revision: "1",
        savedAt: "2026-10-16T08:11:00Z",
      },
    });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <StrictMode>{children}</StrictMode>
    );
    const { result } = renderHook(
      () => useAttemptCoordinator({ attempt, client: api }),
      { wrapper },
    );
    act(() => {
      result.current.changeAnswer(1, {
        type: "SINGLE_CHOICE",
        optionLabel: "A",
      });
    });
    await act(async () => vi.advanceTimersByTimeAsync(650));
    expect(api.save).toHaveBeenCalledTimes(1);
    expect(result.current.statuses[1]?.phase).toBe("saved");
  });

  it("serializes typing during an in-flight save and uses the acknowledged revision for the newer intent", async () => {
    vi.useFakeTimers();
    const api = client();
    const first = deferred<Awaited<ReturnType<AttemptApiClient["save"]>>>();
    vi.mocked(api.save)
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce({
        ok: true,
        value: {
          attemptId: attempt.attemptId,
          questionNumber: 1,
          revision: "2",
          savedAt: "2026-10-16T08:11:02Z",
        },
      });
    const { result } = renderHook(() =>
      useAttemptCoordinator({ attempt, client: api }),
    );

    act(() => {
      result.current.changeAnswer(1, {
        type: "SINGLE_CHOICE",
        optionLabel: "A",
      });
    });
    await act(async () => vi.advanceTimersByTimeAsync(650));
    expect(api.save).toHaveBeenCalledTimes(1);
    expect(vi.mocked(api.save).mock.calls[0]?.[2]).toMatchObject({
      expectedRevision: "0",
    });

    act(() => {
      result.current.changeAnswer(1, {
        type: "SINGLE_CHOICE",
        optionLabel: "B",
      });
    });
    await act(async () => vi.advanceTimersByTimeAsync(650));
    expect(api.save).toHaveBeenCalledTimes(1);
    await act(async () => {
      first.resolve({
        ok: true,
        value: {
          attemptId: attempt.attemptId,
          questionNumber: 1,
          revision: "1",
          savedAt: "2026-10-16T08:11:01Z",
        },
      });
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(api.save).toHaveBeenCalledTimes(2);
    expect(vi.mocked(api.save).mock.calls[1]?.[2]).toEqual({
      expectedRevision: "1",
      answer: { type: "SINGLE_CHOICE", optionLabel: "B" },
    });
    expect(result.current.statuses[1]?.phase).toBe("saved");
    expect(result.current.answers[1]).toEqual({
      type: "SINGLE_CHOICE",
      optionLabel: "B",
    });
    expect(result.current.attempt.revision).toBe("2");
  });

  it("returns the acknowledged revision and current drafts when a terminal action flushes a pending save", async () => {
    vi.useFakeTimers();
    const api = client();
    vi.mocked(api.save).mockResolvedValue({
      ok: true,
      value: {
        attemptId: attempt.attemptId,
        questionNumber: 1,
        revision: "1",
        savedAt: "2026-10-16T08:11:00Z",
      },
    });
    const { result } = renderHook(() =>
      useAttemptCoordinator({ attempt, client: api }),
    );

    act(() => {
      result.current.changeAnswer(1, {
        type: "SINGLE_CHOICE",
        optionLabel: "A",
      });
    });
    let snapshot: Awaited<ReturnType<typeof result.current.flushOrdinarySaves>>;
    await act(async () => {
      snapshot = await result.current.flushOrdinarySaves();
    });

    expect(api.save).toHaveBeenCalledTimes(1);
    expect(snapshot!).toEqual({
      attempt: expect.objectContaining({ revision: "1" }),
      answers: {
        1: { type: "SINGLE_CHOICE", optionLabel: "A" },
      },
    });
  });

  it("retains the exact key and payload when retrying an unknown save outcome", async () => {
    vi.useFakeTimers();
    const api = client();
    vi.mocked(api.save)
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "unavailable", reason: "timeout" },
      })
      .mockResolvedValueOnce({
        ok: true,
        value: {
          attemptId: attempt.attemptId,
          questionNumber: 1,
          revision: "1",
          savedAt: "2026-10-16T08:11:00Z",
        },
      });
    const { result } = renderHook(() =>
      useAttemptCoordinator({ attempt, client: api }),
    );
    act(() => {
      result.current.changeAnswer(1, {
        type: "SINGLE_CHOICE",
        optionLabel: "A",
      });
    });
    await act(async () => vi.advanceTimersByTimeAsync(650));
    expect(result.current.statuses[1]?.phase).toBe("uncertain");
    const original = vi.mocked(api.save).mock.calls[0]!;
    await act(async () => result.current.retryUncertain(1));
    const retry = vi.mocked(api.save).mock.calls[1]!;
    expect(retry.slice(0, 4)).toEqual(original.slice(0, 4));
    expect(result.current.statuses[1]?.phase).toBe("saved");
  });

  it("keeps a newer edit actionable while resolving an earlier unknown save", async () => {
    vi.useFakeTimers();
    const api = client();
    vi.mocked(api.save)
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "unavailable", reason: "timeout" },
      })
      .mockResolvedValueOnce({
        ok: true,
        value: {
          attemptId: attempt.attemptId,
          questionNumber: 1,
          revision: "1",
          savedAt: "2026-10-16T08:11:00Z",
        },
      })
      .mockResolvedValueOnce({
        ok: true,
        value: {
          attemptId: attempt.attemptId,
          questionNumber: 1,
          revision: "2",
          savedAt: "2026-10-16T08:11:01Z",
        },
      });
    const { result } = renderHook(() =>
      useAttemptCoordinator({ attempt, client: api }),
    );
    act(() => {
      result.current.changeAnswer(1, {
        type: "SINGLE_CHOICE",
        optionLabel: "A",
      });
    });
    await act(async () => vi.advanceTimersByTimeAsync(650));
    act(() => {
      result.current.changeAnswer(1, {
        type: "SINGLE_CHOICE",
        optionLabel: "B",
      });
    });
    expect(result.current.statuses[1]?.phase).toBe("uncertain");

    await act(async () => result.current.retryUncertain(1));
    expect(result.current.statuses[1]?.phase).toBe("draft");
    expect(result.current.answers[1]).toEqual({
      type: "SINGLE_CHOICE",
      optionLabel: "B",
    });
    await act(async () => result.current.retryFailed(1));
    expect(vi.mocked(api.save).mock.calls[2]?.[2]).toEqual({
      expectedRevision: "1",
      answer: { type: "SINGLE_CHOICE", optionLabel: "B" },
    });
    expect(result.current.statuses[1]?.phase).toBe("saved");
  });

  it("refreshes a stale revision while preserving the local draft for deliberate resolution", async () => {
    vi.useFakeTimers();
    const api = client();
    vi.mocked(api.save)
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 409, code: "REVISION_CONFLICT" },
      })
      .mockResolvedValueOnce({
        ok: true,
        value: {
          attemptId: attempt.attemptId,
          questionNumber: 1,
          revision: "2",
          savedAt: "2026-10-16T08:12:00Z",
        },
      });
    vi.mocked(api.getAttempt).mockResolvedValue({
      ok: true,
      value: {
        ...attempt,
        revision: "1",
        answers: [
          {
            questionNumber: 1,
            answer: { type: "SINGLE_CHOICE", optionLabel: "A" },
          },
        ],
      },
    });
    const { result } = renderHook(() =>
      useAttemptCoordinator({ attempt, client: api }),
    );
    act(() => {
      result.current.changeAnswer(1, {
        type: "SINGLE_CHOICE",
        optionLabel: "B",
      });
    });
    await act(async () => vi.advanceTimersByTimeAsync(650));
    expect(result.current.statuses[1]?.phase).toBe("conflict");
    expect(result.current.attempt.revision).toBe("1");
    expect(result.current.answers[1]).toEqual({
      type: "SINGLE_CHOICE",
      optionLabel: "B",
    });
    expect(api.save).toHaveBeenCalledTimes(1);

    await act(async () => result.current.saveConflict(1));
    expect(vi.mocked(api.save).mock.calls[1]?.[2]).toEqual({
      expectedRevision: "1",
      answer: { type: "SINGLE_CHOICE", optionLabel: "B" },
    });
    expect(vi.mocked(api.save).mock.calls[1]?.[3]).not.toBe(
      vi.mocked(api.save).mock.calls[0]?.[3],
    );
  });

  it("adopts fresh remote answers only for locally untouched saved questions", async () => {
    vi.useFakeTimers();
    const api = client();
    const secondQuestionAttempt: AttemptView = {
      ...attempt,
      questions: [
        ...attempt.questions,
        {
          number: 2,
          type: "SINGLE_CHOICE",
          stemMarkdown: "Second",
          options: [
            { label: "A", markdown: "Alpha" },
            { label: "B", markdown: "Beta" },
          ],
        },
      ],
      answers: [
        ...attempt.answers,
        {
          questionNumber: 2,
          answer: { type: "SINGLE_CHOICE", optionLabel: null },
        },
      ],
    };
    vi.mocked(api.save).mockResolvedValue({
      ok: false,
      error: { kind: "api", status: 409, code: "REVISION_CONFLICT" },
    });
    vi.mocked(api.getAttempt).mockResolvedValue({
      ok: true,
      value: {
        ...secondQuestionAttempt,
        revision: "1",
        answers: [
          {
            questionNumber: 1,
            answer: { type: "SINGLE_CHOICE", optionLabel: "A" },
          },
          {
            questionNumber: 2,
            answer: { type: "SINGLE_CHOICE", optionLabel: "B" },
          },
        ],
      },
    });
    const { result } = renderHook(() =>
      useAttemptCoordinator({ attempt: secondQuestionAttempt, client: api }),
    );
    act(() => {
      result.current.changeAnswer(1, {
        type: "SINGLE_CHOICE",
        optionLabel: "B",
      });
    });
    await act(async () => vi.advanceTimersByTimeAsync(650));

    expect(result.current.answers[1]).toEqual({
      type: "SINGLE_CHOICE",
      optionLabel: "B",
    });
    expect(result.current.answers[2]).toEqual({
      type: "SINGLE_CHOICE",
      optionLabel: "B",
    });
    expect(result.current.statuses[1]?.phase).toBe("conflict");
    expect(result.current.statuses[2]?.phase).toBe("saved");
  });

  it("preserves the complete local draft set during terminal revision reconciliation", async () => {
    vi.useFakeTimers();
    const api = client();
    vi.mocked(api.getAttempt).mockResolvedValue({
      ok: true,
      value: {
        ...attempt,
        revision: "1",
        answers: [
          {
            questionNumber: 1,
            answer: { type: "SINGLE_CHOICE", optionLabel: "A" },
          },
        ],
      },
    });
    const { result } = renderHook(() =>
      useAttemptCoordinator({ attempt, client: api }),
    );
    act(() => {
      result.current.changeAnswer(1, {
        type: "SINGLE_CHOICE",
        optionLabel: "B",
      });
    });
    await act(async () => {
      await result.current.refreshPreservingDrafts();
    });

    expect(result.current.attempt.revision).toBe("1");
    expect(result.current.answers[1]).toEqual({
      type: "SINGLE_CHOICE",
      optionLabel: "B",
    });
    expect(result.current.statuses[1]?.phase).toBe("conflict");
  });

  it("rejects a delayed GET snapshot that predates an acknowledged save", async () => {
    vi.useFakeTimers();
    const api = client();
    const staleRead =
      deferred<Awaited<ReturnType<AttemptApiClient["getAttempt"]>>>();
    vi.mocked(api.getAttempt).mockReturnValue(staleRead.promise);
    vi.mocked(api.save).mockResolvedValue({
      ok: true,
      value: {
        attemptId: attempt.attemptId,
        questionNumber: 1,
        revision: "1",
        savedAt: "2026-10-16T08:11:00Z",
      },
    });
    const { result } = renderHook(() =>
      useAttemptCoordinator({ attempt, client: api }),
    );

    let refresh!: ReturnType<typeof result.current.refresh>;
    act(() => {
      refresh = result.current.refresh();
      result.current.changeAnswer(1, {
        type: "SINGLE_CHOICE",
        optionLabel: "A",
      });
    });
    await act(async () => vi.advanceTimersByTimeAsync(650));
    expect(result.current.attempt.revision).toBe("1");
    await act(async () => {
      staleRead.resolve({ ok: true, value: attempt });
      await refresh;
    });

    expect(result.current.attempt.revision).toBe("1");
    expect(result.current.answers[1]).toEqual({
      type: "SINGLE_CHOICE",
      optionLabel: "A",
    });
    expect(result.current.statuses[1]?.phase).toBe("saved");
  });

  it("cancels pending autosave and fences deliberate saves while authority is paused", async () => {
    vi.useFakeTimers();
    const api = client();
    vi.mocked(api.save).mockResolvedValue({
      ok: true,
      value: {
        attemptId: attempt.attemptId,
        questionNumber: 1,
        revision: "1",
        savedAt: "2026-10-16T08:11:00Z",
      },
    });
    const { result } = renderHook(() =>
      useAttemptCoordinator({ attempt, client: api }),
    );
    act(() => {
      result.current.changeAnswer(1, {
        type: "SINGLE_CHOICE",
        optionLabel: "A",
      });
      result.current.setMutationPaused(true);
    });
    await act(async () => vi.advanceTimersByTimeAsync(650));
    await act(async () => result.current.retryFailed(1));
    expect(api.save).not.toHaveBeenCalled();
    expect(result.current.statuses[1]?.phase).toBe("draft");

    act(() => result.current.setMutationPaused(false));
    await act(async () => result.current.retryFailed(1));
    expect(api.save).toHaveBeenCalledTimes(1);
    expect(result.current.statuses[1]?.phase).toBe("saved");
  });
});
