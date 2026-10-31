import { afterEach, describe, expect, it, vi } from "vitest";
import { ObservationQueue } from "./observation-queue";
import type { AnswerObservation } from "../../teacher-results/api/teacher-assessment-contract.generated";

afterEach(() => vi.useRealTimers());
const single = (optionLabel: "A" | "C" | null) => ({
  type: "SINGLE_CHOICE" as const,
  optionLabel,
});
describe("optional session observation queue", () => {
  it("is off until explicit start and preserves unsaved A to C and clear without SAVE", async () => {
    const sent: AnswerObservation[] = [];
    const queue = new ObservationQueue(async (e) => {
      sent.push(e);
      return 200;
    }, vi.fn());
    queue.answer(1, single(null), single("A"));
    expect(sent).toEqual([]);
    queue.start(crypto.randomUUID());
    queue.navigation("QUESTION_VIEWED", 1);
    queue.answer(1, single(null), single("A"));
    queue.answer(1, single("A"), single("C"));
    queue.answer(1, single("C"), single(null));
    await vi.waitFor(() => expect(sent).toHaveLength(4));
    expect(sent.map((e) => e.type)).toEqual([
      "QUESTION_VIEWED",
      "ANSWER_SELECTED",
      "ANSWER_CHANGED",
      "ANSWER_CLEARED",
    ]);
    expect(sent[2]!.transition).toEqual({
      fromOptionLabels: ["A"],
      toOptionLabels: ["C"],
    });
    expect(sent.every((e) => Number.isSafeInteger(e.sequence))).toBe(true);
    expect(new Set(sent.map((e) => e.eventId)).size).toBe(4);
    queue.stop();
  });
  it("never captures numeric or matrix values and ignores no-op choices", () => {
    const send = vi.fn(async () => 200),
      queue = new ObservationQueue(send, vi.fn());
    queue.start(crypto.randomUUID());
    queue.answer(
      4,
      { type: "NUMERIC_FILL", value: "0001" },
      { type: "NUMERIC_FILL", value: "0002" },
    );
    queue.answer(
      3,
      {
        type: "TRUE_FALSE_MATRIX",
        judgments: { A: null, B: null, C: null, D: null },
      },
      {
        type: "TRUE_FALSE_MATRIX",
        judgments: { A: true, B: null, C: null, D: null },
      },
    );
    queue.answer(1, single("A"), single("A"));
    expect(send).not.toHaveBeenCalled();
    queue.stop();
  });
  it("retries identical IDs/canonical body with bounded exponential backoff, never indefinitely", async () => {
    vi.useFakeTimers();
    const send = vi
        .fn<
          (event: AnswerObservation, signal: AbortSignal) => Promise<number>
        >()
        .mockResolvedValue(503),
      gap = vi.fn();
    const queue = new ObservationQueue(send, gap);
    queue.start(crypto.randomUUID());
    queue.answer(1, single(null), single("A"));
    await vi.advanceTimersByTimeAsync(8000);
    expect(send).toHaveBeenCalledTimes(4);
    expect(
      new Set(send.mock.calls.map((args) => JSON.stringify(args[0]))).size,
    ).toBe(1);
    expect(gap).toHaveBeenCalledTimes(1);
    queue.stop();
  });
  it("stops on rejected authority and withdrawal cancels pending backoff", async () => {
    vi.useFakeTimers();
    const denied = vi.fn(async () => 401),
      gap = vi.fn();
    const queue = new ObservationQueue(denied, gap);
    queue.start(crypto.randomUUID());
    queue.navigation("QUESTION_VIEWED", 1);
    await vi.advanceTimersByTimeAsync(0);
    queue.answer(1, single(null), single("A"));
    expect(denied).toHaveBeenCalledTimes(1);
    const transient = vi.fn(async () => 429),
      later = new ObservationQueue(transient, gap);
    later.start(crypto.randomUUID());
    later.navigation("QUESTION_VIEWED", 1);
    await vi.advanceTimersByTimeAsync(0);
    later.stop();
    await vi.advanceTimersByTimeAsync(30000);
    expect(transient).toHaveBeenCalledTimes(1);
  });
  it("bounds the pending queue while a request is stalled and discards it on stop", async () => {
    let finish!: (status: number) => void;
    const send = vi.fn(
        () =>
          new Promise<number>((resolve) => {
            finish = resolve;
          }),
      ),
      gap = vi.fn();
    const queue = new ObservationQueue(send, gap);
    queue.start(crypto.randomUUID());
    for (let i = 0; i < 105; i++) queue.navigation("QUESTION_VIEWED", 1);
    expect(send).toHaveBeenCalledTimes(1);
    expect(gap).toHaveBeenCalledTimes(5);
    queue.stop();
    finish(200);
    await Promise.resolve();
    expect(send).toHaveBeenCalledTimes(1);
  });
});
