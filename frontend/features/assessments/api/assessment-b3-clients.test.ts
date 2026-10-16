import { describe, expect, it, vi } from "vitest";
import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
  ReplayableAuthenticatedRequest,
} from "../../../lib/api/authenticated-request";
import { createClosureApiClient } from "../../closures/api/closure-api-client";
import {
  canCancelClosure,
  canCompleteClosure,
  closureViewSchema,
} from "../../closures/model/closure";
import { createNotificationApiClient } from "../../notifications/api/notification-api-client";
import {
  confirmedCutoff,
  cutoffRemainingGuidance,
  notificationSchema,
} from "../../notifications/model/notification";
import { createTeacherResultsApiClient } from "../../teacher-results/api/teacher-results-api-client";
import {
  formatWeightTotal,
  teacherResultRowSchema,
} from "../../teacher-results/model/teacher-results";

const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const closureRequestId = "f7b14962-3a2e-4d2d-926a-3b36ea90c198";
const attemptId = "a7b14962-3a2e-4d2d-926a-3b36ea90c197";
const studentId = "b7b14962-3a2e-4d2d-926a-3b36ea90c196";
const teacherId = "c7b14962-3a2e-4d2d-926a-3b36ea90c195";
const notificationId = "d7b14962-3a2e-4d2d-926a-3b36ea90c194";
const resultId = "e7b14962-3a2e-4d2d-926a-3b36ea90c193";
const key = "closure_operation_123456";

const closure = {
  closureRequestId,
  publicationId,
  requestingTeacherId: teacherId,
  requestedAt: "2026-10-16T08:30:00Z",
  state: "PENDING" as const,
  finalizationStartedAt: null,
  resolvedAt: null,
  publicationRevision: 4,
};

const notification = {
  notificationId,
  publicationId,
  publicationTitle: "Network assessment",
  attemptId,
  closureRequestId,
  teacherInitiated: true as const,
  contentVersion: 1 as const,
  publishedAt: "2026-10-16T08:31:00Z",
  confirmedNoticeAt: "2026-10-16T08:32:00Z",
  closureCutoffAt: "2026-10-18T08:32:00Z",
  warningHours: 48 as const,
  noticeStatus: "NOTICE_CONFIRMED" as const,
  message: "You have at least 48 hours from confirmation.",
  resultId: null,
  serverNow: "2026-10-16T09:32:00Z",
};

const result = {
  resultId,
  attemptId,
  publicationId,
  ordinal: "9007199254740993",
  earnedWeight: "12345678901234567890.125",
  possibleWeight: "12345678901234567891",
  percentageDisplay: "100.00",
  gradingPolicyVersion: 1 as const,
  gradingPolicyId: "QUIZOPIA_GRADING_V1" as const,
  terminalReason: "TEACHER_NOTICE_CUTOFF" as const,
  finalizedAt: "2026-10-18T08:32:00Z",
};

function response(status: number, value: unknown): AuthenticatedRequestResult {
  return {
    kind: "response",
    response: {
      status,
      ok: status >= 200 && status < 300,
      headers: new Headers({ "cache-control": "no-store" }),
      body: { kind: "json", value },
    },
  };
}

function harness(initialGeneration = 1) {
  let generation: number | null = initialGeneration;
  const requests: ReplayableAuthenticatedRequest[] = [];
  const replies: Array<
    AuthenticatedRequestResult | Promise<AuthenticatedRequestResult>
  > = [];
  const execute = vi.fn<AuthenticatedRequestExecutor["execute"]>(
    async (request) => {
      requests.push(request);
      const reply = replies.shift();
      if (reply === undefined) throw new Error("Missing test response");
      return reply;
    },
  );
  const authenticatedRequests: AuthenticatedRequestExecutor = {
    execute,
    executeOnce: vi.fn(),
    executeOnceWithAccessToken: vi.fn(),
  };
  const options = {
    authenticatedRequests,
    getSessionGeneration: () => generation,
    apiBaseUrl: "http://localhost:8080",
  };
  return {
    closureClient: createClosureApiClient(options),
    notificationClient: createNotificationApiClient(options),
    resultsClient: createTeacherResultsApiClient(options),
    requests,
    replies,
    setGeneration(next: number | null) {
      generation = next;
    },
  };
}

describe("W3-B3 Assessment clients", () => {
  it("sends exact integer TeacherCommand and one durable key for creation and replay", async () => {
    const test = harness();
    test.replies.push(response(201, closure), response(200, closure));

    await expect(
      test.closureClient.request(publicationId, { expectedRevision: 3 }, key),
    ).resolves.toMatchObject({
      ok: true,
      status: 201,
      value: { created: true, view: closure },
    });
    await expect(
      test.closureClient.request(publicationId, { expectedRevision: 3 }, key),
    ).resolves.toMatchObject({
      ok: true,
      status: 200,
      value: { created: false, view: closure },
    });

    for (const request of test.requests) {
      const wire = request.createRequest();
      expect(wire.method).toBe("POST");
      expect(String(wire.target)).toBe(
        `http://localhost:8080/api/assessments/publications/${publicationId}/closure-requests`,
      );
      expect(wire.body).toBe('{"expectedRevision":3}');
      expect(new Headers(wire.headers).get("Idempotency-Key")).toBe(key);
    }
  });

  it("fails closed before sending an unsafe JavaScript revision", async () => {
    const test = harness();
    await expect(
      test.closureClient.request(
        publicationId,
        { expectedRevision: Number.MAX_SAFE_INTEGER + 1 },
        key,
      ),
    ).resolves.toEqual({ ok: false, error: { kind: "invalid-input" } });
    expect(test.requests).toHaveLength(0);
  });

  it("accepts immutable COMPLETE 202 and binds the response to the exact request", async () => {
    const test = harness();
    test.replies.push(
      response(202, {
        closureRequestId,
        publicationId,
        closed: false,
        publicationRevision: 7,
      }),
    );
    await expect(
      test.closureClient.complete(
        publicationId,
        closureRequestId,
        { expectedRevision: 7 },
        key,
      ),
    ).resolves.toMatchObject({
      ok: true,
      status: 202,
      value: { closed: false },
    });
  });

  it("surfaces conflicts and dependency 503 without feature-level session mutation", async () => {
    const test = harness();
    test.replies.push(
      response(409, { code: "IDEMPOTENCY_CONFLICT" }),
      response(503, { code: "AUTHORITY_UNAVAILABLE" }),
    );
    await expect(
      test.closureClient.cancel(
        publicationId,
        closureRequestId,
        { expectedRevision: 4 },
        key,
      ),
    ).resolves.toEqual({
      ok: false,
      error: { kind: "api", status: 409, code: "IDEMPOTENCY_CONFLICT" },
    });
    await expect(test.notificationClient.list()).resolves.toEqual({
      ok: false,
      error: { kind: "api", status: 503, code: "AUTHORITY_UNAVAILABLE" },
    });
    expect(test.requests).toHaveLength(2);
  });

  it("preserves the operation key while exposing a conflicting Teacher payload", async () => {
    const test = harness();
    test.replies.push(
      response(201, closure),
      response(409, { code: "IDEMPOTENCY_CONFLICT" }),
    );
    await test.closureClient.request(
      publicationId,
      { expectedRevision: 3 },
      key,
    );
    await expect(
      test.closureClient.request(publicationId, { expectedRevision: 4 }, key),
    ).resolves.toEqual({
      ok: false,
      error: { kind: "api", status: 409, code: "IDEMPOTENCY_CONFLICT" },
    });
    const wires = test.requests.map((request) => request.createRequest());
    expect(wires.map((wire) => wire.body)).toEqual([
      '{"expectedRevision":3}',
      '{"expectedRevision":4}',
    ]);
    expect(
      wires.every(
        (wire) => new Headers(wire.headers).get("Idempotency-Key") === key,
      ),
    ).toBe(true);
  });

  it("passes opaque cursors only through supported list query fields", async () => {
    const test = harness();
    const cursor = "v1_actor-bound.payload_-";
    test.replies.push(
      response(200, { items: [notification], nextCursor: null }),
    );
    await expect(
      test.notificationClient.list({ limit: 37, cursor }),
    ).resolves.toMatchObject({ ok: true });
    const wire = test.requests[0]!.createRequest();
    expect(wire.method).toBe("GET");
    expect(wire.body).toBeUndefined();
    expect(String(wire.target)).toBe(
      `http://localhost:8080/api/assessments/notifications?limit=37&cursor=${cursor}`,
    );
  });

  it("masks another recipient's notice and rejects a mismatched returned identity", async () => {
    const denied = harness();
    denied.replies.push(response(404, { code: "NOTIFICATION_NOT_FOUND" }));
    await expect(
      denied.notificationClient.get(notificationId),
    ).resolves.toEqual({
      ok: false,
      error: { kind: "api", status: 404, code: "NOTIFICATION_NOT_FOUND" },
    });

    const mismatched = harness();
    mismatched.replies.push(
      response(200, {
        ...notification,
        notificationId: "f7b14962-3a2e-4d2d-926a-3b36ea90c191",
      }),
    );
    await expect(
      mismatched.notificationClient.get(notificationId),
    ).resolves.toEqual({ ok: false, error: { kind: "invalid-input" } });
  });

  it("fences a response that resolves after an account/session switch", async () => {
    let resolve!: (value: AuthenticatedRequestResult) => void;
    const delayed = new Promise<AuthenticatedRequestResult>((complete) => {
      resolve = complete;
    });
    const test = harness();
    test.replies.push(delayed);
    const read = test.notificationClient.list();
    test.setGeneration(2);
    resolve(response(200, { items: [notification], nextCursor: null }));
    await expect(read).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
  });

  it("preserves exact decimal result strings and rejects answer-key leakage", async () => {
    const test = harness();
    test.replies.push(
      response(200, {
        items: [
          {
            attemptId,
            studentId,
            ordinal: result.ordinal,
            state: "SUBMITTED",
            result,
          },
        ],
        nextCursor: "next.cursor",
      }),
      response(200, {
        items: [
          {
            attemptId,
            studentId,
            ordinal: "1",
            state: "SUBMITTED",
            result: { ...result, ordinal: "1", correctAnswer: "forbidden" },
          },
        ],
        nextCursor: null,
      }),
    );
    const accepted = await test.resultsClient.results(publicationId);
    expect(accepted).toMatchObject({ ok: true });
    if (accepted.ok) {
      expect(accepted.value.items[0]?.ordinal).toBe("9007199254740993");
      expect(accepted.value.items[0]?.result?.earnedWeight).toBe(
        "12345678901234567890.125",
      );
    }
    await expect(test.resultsClient.results(publicationId)).resolves.toEqual({
      ok: false,
      error: { kind: "invalid-response", status: 200 },
    });
  });
});

describe("W3-B3 projection invariants", () => {
  it("permits every durable closure state with exact lifecycle controls", () => {
    for (const state of [
      "PENDING",
      "FINALIZING",
      "CANCELLED",
      "COMPLETED",
      "RESOLVED_EARLY",
    ] as const) {
      const parsed = closureViewSchema.parse({
        ...closure,
        state,
        finalizationStartedAt:
          state === "FINALIZING" || state === "COMPLETED"
            ? "2026-10-18T08:32:00Z"
            : null,
        resolvedAt:
          state === "CANCELLED" ||
          state === "COMPLETED" ||
          state === "RESOLVED_EARLY"
            ? "2026-10-18T08:35:00Z"
            : null,
      });
      expect(canCompleteClosure(parsed)).toBe(
        state === "PENDING" || state === "FINALIZING",
      );
      expect(canCancelClosure(parsed)).toBe(state === "PENDING");
    }
  });

  it("creates no cutoff for PREPARING and accepts exactly 48 authoritative hours", () => {
    const preparing = notificationSchema.parse({
      ...notification,
      confirmedNoticeAt: null,
      closureCutoffAt: null,
      noticeStatus: "PREPARING",
    });
    expect(confirmedCutoff(preparing)).toBeNull();
    const confirmed = notificationSchema.parse(notification);
    expect(confirmedCutoff(confirmed)).toEqual({
      confirmedNoticeAt: notification.confirmedNoticeAt,
      closureCutoffAt: notification.closureCutoffAt,
    });
    expect(
      cutoffRemainingGuidance(confirmed.serverNow, confirmed.closureCutoffAt!),
    ).toContain("47 hours");
  });

  it("rejects fabricated or contradictory cutoff evidence", () => {
    expect(
      notificationSchema.safeParse({
        ...notification,
        noticeStatus: "PREPARING",
      }).success,
    ).toBe(false);
    expect(
      notificationSchema.safeParse({
        ...notification,
        closureCutoffAt: "2026-10-18T08:31:59Z",
      }).success,
    ).toBe(false);
  });

  it("allows nullable unfinalized results and rejects contradictory ACTIVE scores", () => {
    const active = teacherResultRowSchema.parse({
      attemptId,
      studentId,
      ordinal: "1",
      state: "ACTIVE",
      result: null,
    });
    expect(formatWeightTotal(active)).toBeNull();
    expect(
      teacherResultRowSchema.safeParse({ ...active, result }).success,
    ).toBe(false);
    const submitted = teacherResultRowSchema.parse({
      attemptId,
      studentId,
      ordinal: result.ordinal,
      state: "SUBMITTED",
      result,
    });
    expect(formatWeightTotal(submitted)).toBe(
      "12345678901234567890.125 / 12345678901234567891",
    );
  });

  it("rejects Teacher result ordinals above the signed 64-bit contract bound", () => {
    expect(
      teacherResultRowSchema.safeParse({
        attemptId,
        studentId,
        ordinal: "9223372036854775808",
        state: "ACTIVE",
        result: null,
      }).success,
    ).toBe(false);
    expect(
      teacherResultRowSchema.safeParse({
        attemptId,
        studentId,
        ordinal: "9223372036854775807",
        state: "ACTIVE",
        result: null,
      }).success,
    ).toBe(true);
    for (const malformed of ["abc", "1.2", "1e3", ""]) {
      expect(() =>
        teacherResultRowSchema.safeParse({
          attemptId,
          studentId,
          ordinal: malformed,
          state: "ACTIVE",
          result: null,
        }),
      ).not.toThrow();
      expect(
        teacherResultRowSchema.safeParse({
          attemptId,
          studentId,
          ordinal: malformed,
          state: "ACTIVE",
          result: null,
        }).success,
      ).toBe(false);
    }
  });
});
