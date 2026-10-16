import { describe, expect, it, vi } from "vitest";
import type { MonitoringApiClient } from "../api/monitoring-api-client";
import { createActivityEvidenceStream } from "./activity-evidence-stream";

const session = {
  id: "22222222-2222-4222-8222-222222222222",
  attemptId: "11111111-1111-4111-8111-111111111111",
  publicationId: "44444444-4444-4444-8444-444444444444",
  classroomId: "55555555-5555-4555-8555-555555555555",
  studentId: "66666666-6666-4666-8666-666666666666",
  deadlineAt: "2026-10-16T12:00:00Z",
  status: "ACTIVE" as const,
  createdAt: "2026-10-16T10:00:00Z",
  endedAt: null,
};

function api(append: MonitoringApiClient["appendEvent"]) {
  return {
    appendEvent: append,
    reconcile: vi.fn(async () => ({ ok: true as const, value: session })),
  } as unknown as MonitoringApiClient;
}

describe("in-memory activity evidence stream", () => {
  it("assigns one stream, monotonic sequences and excludes answer payloads", async () => {
    const seen: unknown[] = [];
    const client = api(
      vi.fn(async (_session, event) => {
        seen.push(event);
        return {
          ok: true as const,
          value: {
            id: event.eventId,
            ordinal: seen.length,
            receivedAt: "2026-10-16T10:00:01Z",
          },
        };
      }),
    );
    let id = 0;
    const stream = createActivityEvidenceStream({
      api: client,
      session,
      getSessionGeneration: () => 1,
      sessionGeneration: 1,
      createUuid: () =>
        `${String(++id).padStart(8, "0")}-0000-4000-8000-000000000000`,
      clock: () => Date.parse("2026-10-16T10:00:00Z"),
    });
    expect(stream.observe({ type: "QUESTION_VIEWED", questionNumber: 4 })).toBe(
      true,
    );
    expect(stream.observe({ type: "ANSWER_CHANGED", questionNumber: 4 })).toBe(
      true,
    );
    await vi.waitFor(() => expect(seen).toHaveLength(2));
    expect(seen).toMatchObject([
      { sequence: 0, questionNumber: 4, schemaVersion: 1 },
      { sequence: 1, questionNumber: 4, schemaVersion: 1 },
    ]);
    expect(
      new Set(seen.map((value) => (value as { streamId: string }).streamId))
        .size,
    ).toBe(1);
    expect(JSON.stringify(seen)).not.toContain("answerText");
  });

  it("stops automatic retries after uncertainty and replays the immutable event once after reconciliation", async () => {
    const append = vi
      .fn<MonitoringApiClient["appendEvent"]>()
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "unavailable", reason: "network" },
      })
      .mockResolvedValueOnce({
        ok: true,
        value: {
          id: "33333333-3333-4333-8333-333333333333",
          ordinal: 2,
          receivedAt: "2026-10-16T10:00:02Z",
        },
      });
    const client = api(append);
    const stream = createActivityEvidenceStream({
      api: client,
      session,
      getSessionGeneration: () => 1,
      sessionGeneration: 1,
      createUuid: (() => {
        const ids = [
          "77777777-7777-4777-8777-777777777777",
          "33333333-3333-4333-8333-333333333333",
        ];
        return () => ids.shift()!;
      })(),
    });
    stream.observe({ type: "WINDOW_BLUR" });
    await vi.waitFor(() =>
      expect(stream.getSnapshot().delivery).toBe("uncertain"),
    );
    expect(append).toHaveBeenCalledTimes(1);
    const original = append.mock.calls[0]![1];
    await stream.reconcile();
    expect(append).toHaveBeenCalledTimes(2);
    expect(append.mock.calls[1]![1]).toEqual(original);
    expect(stream.getSnapshot()).toMatchObject({
      delivery: "idle",
      pendingCount: 0,
    });
  });

  it("does not retry a definitively rejected head when later observations arrive", async () => {
    const append = vi.fn<MonitoringApiClient["appendEvent"]>(async () => ({
      ok: false,
      error: { kind: "api", status: 409, code: "EVENT_CONFLICT" },
    }));
    const stream = createActivityEvidenceStream({
      api: api(append),
      session,
      getSessionGeneration: () => 1,
      sessionGeneration: 1,
    });
    stream.observe({ type: "WINDOW_BLUR" });
    await vi.waitFor(() =>
      expect(stream.getSnapshot().delivery).toBe("blocked"),
    );
    expect(stream.observe({ type: "WINDOW_FOCUS" })).toBe(false);
    expect(stream.observe({ type: "VISIBILITY_HIDDEN" })).toBe(false);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(append).toHaveBeenCalledTimes(1);
    expect(stream.getSnapshot()).toMatchObject({
      delivery: "blocked",
      pendingCount: 0,
    });
  });

  it("drops queued evidence without appending after terminal reconciliation", async () => {
    const append = vi.fn<MonitoringApiClient["appendEvent"]>(async () => ({
      ok: false,
      error: { kind: "unavailable", reason: "network" },
    }));
    const client = api(append);
    vi.mocked(client.reconcile).mockResolvedValue({
      ok: true,
      value: {
        ...session,
        status: "ENDED",
        endedAt: "2026-10-16T10:05:00Z",
      },
    });
    const stream = createActivityEvidenceStream({
      api: client,
      session,
      getSessionGeneration: () => 1,
      sessionGeneration: 1,
    });
    stream.observe({ type: "WINDOW_BLUR" });
    await vi.waitFor(() =>
      expect(stream.getSnapshot().delivery).toBe("uncertain"),
    );
    await stream.reconcile();
    expect(append).toHaveBeenCalledTimes(1);
    expect(stream.getSnapshot()).toMatchObject({
      delivery: "stopped",
      pendingCount: 0,
    });
    expect(stream.observe({ type: "WINDOW_BLUR" })).toBe(false);
  });

  it("bounds memory and drops all pending evidence on account switch", async () => {
    let generation = 1;
    const never = new Promise<never>(() => undefined);
    const append = vi.fn<MonitoringApiClient["appendEvent"]>(() => never);
    const stream = createActivityEvidenceStream({
      api: api(append),
      session,
      getSessionGeneration: () => generation,
      sessionGeneration: 1,
      queueLimit: 2,
    });
    expect(stream.observe({ type: "WINDOW_BLUR" })).toBe(true);
    expect(stream.observe({ type: "WINDOW_FOCUS" })).toBe(true);
    expect(stream.observe({ type: "VISIBILITY_HIDDEN" })).toBe(false);
    expect(stream.getSnapshot()).toMatchObject({
      pendingCount: 2,
      droppedCount: 1,
    });
    generation = 2;
    expect(stream.observe({ type: "WINDOW_FOCUS" })).toBe(false);
    await stream.stop();
    expect(stream.getSnapshot()).toMatchObject({
      pendingCount: 0,
      delivery: "stopped",
    });
  });
});
