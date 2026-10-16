import { describe, expect, it, vi } from "vitest";
import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import type { AttemptView, StudentView } from "./attempt-contract.generated";
import {
  createAttemptApiClient,
  isUncertainAttemptMutation,
} from "./attempt-api-client";

const publicationId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const attemptId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const key = "attempt_operation_123456";
const locator = "P".repeat(43);
const studentView: StudentView = {
  publicationId,
  title: "Private assessment",
  description: null,
  audience: "PUBLIC",
  startAt: "2026-10-16T08:00:00Z",
  endAt: "2026-10-16T09:00:00Z",
  durationMinutes: 40,
  monitoringRequired: false,
  windowStatus: "AVAILABLE",
};
const attempt: AttemptView = {
  attemptId,
  publicationId,
  ordinal: "9223372036854775807",
  state: "ACTIVE",
  startedAt: "2026-10-16T08:00:00Z",
  deadlineAt: "2026-10-16T08:40:00Z",
  durationMinutes: 40,
  revision: "9223372036854775806",
  serverNow: "2026-10-16T08:10:00Z",
  questions: [
    {
      number: 1,
      type: "NUMERIC_FILL",
      stemMarkdown: "Exact value?",
      options: [],
    },
  ],
  answers: [
    { questionNumber: 1, answer: { type: "NUMERIC_FILL", value: null } },
  ],
  closure: null,
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

function harness() {
  const execute = vi.fn<AuthenticatedRequestExecutor["execute"]>();
  const executor: AuthenticatedRequestExecutor = {
    execute,
    executeOnce: vi.fn(),
    executeOnceWithAccessToken: vi.fn(),
  };
  let generation: number | null = 7;
  const client = createAttemptApiClient({
    authenticatedRequests: executor,
    getSessionGeneration: () => generation,
    apiBaseUrl: "http://gateway.test",
  });
  return {
    client,
    execute,
    setGeneration: (next: number | null) => {
      generation = next;
    },
  };
}

describe("Student Attempt API client", () => {
  it("keeps a PUBLIC locator only in the exact entry body and never the URL", async () => {
    const h = harness();
    h.execute.mockImplementation(async (request) => {
      const created = request.createRequest();
      expect(String(created.target)).toBe(
        "http://gateway.test/api/assessments/publications/entry",
      );
      expect(String(created.target)).not.toContain(locator);
      expect(created.body).toBe(JSON.stringify({ locator }));
      expect(new Headers(created.headers).has("Idempotency-Key")).toBe(false);
      return response(200, studentView);
    });
    await expect(h.client.enterPublic({ locator })).resolves.toEqual({
      ok: true,
      value: studentView,
    });
  });

  it("uses bodyless CLASS entry and validates the returned Publication binding", async () => {
    const h = harness();
    h.execute.mockImplementation(async (request) => {
      const created = request.createRequest();
      expect(created.method).toBe("POST");
      expect(created.body).toBeUndefined();
      expect(String(created.target)).toBe(
        `http://gateway.test/api/assessments/publications/${publicationId}/entry`,
      );
      return response(200, { ...studentView, audience: "CLASS" });
    });
    await expect(h.client.enterClass(publicationId)).resolves.toMatchObject({
      ok: true,
    });
  });

  it.each([
    [403, "ACCESS_DENIED"],
    [404, "CLASSROOM_NOT_ACCESSIBLE"],
  ] as const)(
    "preserves server authorization denial HTTP %i without claiming client authority",
    async (status, code) => {
      const h = harness();
      h.execute.mockResolvedValue(response(status, { code }));
      await expect(h.client.enterClass(publicationId)).resolves.toEqual({
        ok: false,
        error: { kind: "api", status, code },
      });
    },
  );

  it("sends START exactly once with the required key and bigint-safe strings", async () => {
    const h = harness();
    h.execute.mockImplementation(async (request) => {
      const created = request.createRequest();
      expect(created.body).toBe(JSON.stringify({ locator }));
      expect(new Headers(created.headers).get("Idempotency-Key")).toBe(key);
      return response(201, attempt);
    });
    const result = await h.client.start(publicationId, { locator }, key);
    expect(result).toEqual({ ok: true, value: attempt });
    expect(result.ok && result.value.revision).toBe("9223372036854775806");
    expect(h.execute).toHaveBeenCalledTimes(1);
  });

  it("preserves exact save payload and maps stale revision conflicts", async () => {
    const h = harness();
    h.execute.mockImplementation(async (request) => {
      const created = request.createRequest();
      expect(created.body).toBe(
        JSON.stringify({
          expectedRevision: "9223372036854775806",
          answer: { type: "NUMERIC_FILL", value: "0001" },
        }),
      );
      return response(409, { code: "REVISION_CONFLICT" });
    });
    await expect(
      h.client.save(
        attemptId,
        1,
        {
          expectedRevision: "9223372036854775806",
          answer: { type: "NUMERIC_FILL", value: "0001" },
        },
        key,
      ),
    ).resolves.toEqual({
      ok: false,
      error: { kind: "api", status: 409, code: "REVISION_CONFLICT" },
    });
  });

  it("uses distinct exact-key RETAKE and ABANDON commands", async () => {
    const h = harness();
    h.execute
      .mockImplementationOnce(async (request) => {
        const created = request.createRequest();
        expect(String(created.target)).toContain(
          `/publications/${publicationId}/attempts/retake`,
        );
        expect(created.body).toBe(JSON.stringify({ locator }));
        expect(new Headers(created.headers).get("Idempotency-Key")).toBe(key);
        return response(201, { ...attempt, ordinal: "2" });
      })
      .mockImplementationOnce(async (request) => {
        const created = request.createRequest();
        expect(String(created.target)).toContain(
          `/attempts/${attemptId}/abandon`,
        );
        expect(created.body).toBe(
          JSON.stringify({ expectedRevision: "9223372036854775806" }),
        );
        expect(new Headers(created.headers).get("Idempotency-Key")).toBe(key);
        return response(200, {
          attemptId,
          state: "ABANDONED",
          terminalReason: "STUDENT_ABANDON",
          revision: "9223372036854775807",
          finalizedAt: "2026-10-16T08:20:00Z",
        });
      });

    await expect(
      h.client.retake(publicationId, { locator }, key),
    ).resolves.toMatchObject({ ok: true, value: { ordinal: "2" } });
    await expect(
      h.client.abandon(
        attemptId,
        { expectedRevision: "9223372036854775806" },
        key,
      ),
    ).resolves.toMatchObject({
      ok: true,
      value: { state: "ABANDONED", terminalReason: "STUDENT_ABANDON" },
    });
  });

  it("fences a late prior-account response by session generation", async () => {
    const h = harness();
    let resolve!: (value: AuthenticatedRequestResult) => void;
    h.execute.mockReturnValue(
      new Promise((complete) => {
        resolve = complete;
      }),
    );
    const pending = h.client.getAttempt(attemptId);
    h.setGeneration(8);
    resolve(response(200, attempt));
    await expect(pending).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
    await expect(h.client.getResult(attemptId)).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
    expect(h.execute).toHaveBeenCalledTimes(1);
  });

  it("treats dependency 503 as uncertain without manufacturing a session failure", async () => {
    const h = harness();
    h.execute.mockResolvedValue(
      response(503, { code: "DEPENDENCY_UNAVAILABLE" }),
    );
    const result = await h.client.submit(
      attemptId,
      { expectedRevision: "0" },
      key,
    );
    expect(result).toEqual({
      ok: false,
      error: { kind: "api", status: 503, code: "DEPENDENCY_UNAVAILABLE" },
    });
    expect(!result.ok && isUncertainAttemptMutation(result.error)).toBe(true);
  });
});
