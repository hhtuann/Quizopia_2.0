import { describe, expect, it, vi } from "vitest";
import type {
  AuthenticatedRequestExecutor,
  ReplayableAuthenticatedRequest,
} from "../../../lib/api/authenticated-request";
import type { HttpResponse } from "../../../lib/api/http-transport";
import {
  createQuizDeletionApiClient,
  isDeletionIdempotencyKeyValid,
  newDeletionIdempotencyKey,
} from "./quiz-deletion-client";

const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const operationId = "1c9d0d7e-5c8a-4b8f-9a55-1d3a6d3a2a11";
const key = "deletion-intent-abcdef01";

function jsonResponse(status: number, value: unknown): HttpResponse {
  return {
    body: { kind: "json", value },
    headers: new Headers({ "content-type": "application/json" }),
    ok: status >= 200 && status < 300,
    status,
  };
}

function errorBody(status: number, code: string) {
  return {
    code,
    message: `${code} message`,
    status,
    path: `/api/quizzes/${quizId}/deletion-requests`,
  };
}

function harness(
  handler: (request: ReplayableAuthenticatedRequest) => Promise<HttpResponse>,
) {
  const execute = vi.fn(async (request: ReplayableAuthenticatedRequest) => ({
    kind: "response" as const,
    response: await handler(request),
  }));
  const executor: AuthenticatedRequestExecutor = {
    execute,
    executeOnce: vi.fn(),
    executeOnceWithAccessToken: vi.fn(),
  };
  return { execute, executor };
}

function client(options: {
  readonly executor: AuthenticatedRequestExecutor;
  readonly generation?: () => number | null;
}) {
  return createQuizDeletionApiClient({
    authenticatedRequests: options.executor,
    getSessionGeneration: options.generation ?? (() => 1),
  });
}

describe("newDeletionIdempotencyKey", () => {
  it("generates canonical [A-Za-z0-9_-] keys of 16-128 characters", () => {
    for (let index = 0; index < 32; index += 1) {
      expect(isDeletionIdempotencyKeyValid(newDeletionIdempotencyKey())).toBe(
        true,
      );
    }
  });

  it("never generates the same key twice", () => {
    const seen = new Set(Array.from({ length: 64 }, newDeletionIdempotencyKey));
    expect(seen.size).toBe(64);
  });
});

describe("quiz deletion client", () => {
  it("posts the deletion request with the Idempotency-Key header and parses the 202", async () => {
    const observed: Array<{
      readonly method?: string;
      readonly target: string;
      readonly idempotencyKey: string | null;
      readonly body?: string;
    }> = [];
    const { execute, executor } = harness(async (request) => {
      const built = request.createRequest();
      observed.push({
        method: built.method,
        target: String(built.target),
        idempotencyKey: new Headers(built.headers).get("Idempotency-Key"),
        body: typeof built.body === "string" ? built.body : undefined,
      });
      return jsonResponse(202, { operationId, status: "QUIZ_FENCED" });
    });
    const api = client({ executor });

    await expect(
      api.requestDeletion(quizId, {
        confirmTitle: "Network quiz",
        idempotencyKey: key,
      }),
    ).resolves.toEqual({
      ok: true,
      value: { operationId, status: "QUIZ_FENCED" },
    });
    expect(observed).toEqual([
      {
        method: "POST",
        target: `/api/quizzes/${quizId}/deletion-requests`,
        idempotencyKey: key,
        body: JSON.stringify({ confirmTitle: "Network quiz" }),
      },
    ]);
    expect(execute).toHaveBeenCalledTimes(1);
  });

  it("replays the SAME key on retry so the backend can deduplicate the intent", async () => {
    let calls = 0;
    const keys: string[] = [];
    const { executor } = harness(async (request) => {
      calls += 1;
      const built = request.createRequest();
      keys.push(new Headers(built.headers).get("Idempotency-Key") ?? "");
      if (calls === 1) {
        return jsonResponse(409, errorBody(409, "DELETION_CONFLICT"));
      }
      return jsonResponse(202, { operationId, status: "REQUESTED" });
    });
    const api = client({ executor });

    const first = await api.requestDeletion(quizId, {
      confirmTitle: "Network quiz",
      idempotencyKey: key,
    });
    expect(first).toMatchObject({
      ok: false,
      error: { kind: "api-error", error: { code: "DELETION_CONFLICT" } },
    });

    const second = await api.requestDeletion(quizId, {
      confirmTitle: "Network quiz",
      idempotencyKey: key,
    });
    expect(second).toEqual({
      ok: true,
      value: { operationId, status: "REQUESTED" },
    });
    expect(keys).toEqual([key, key]);
  });

  it.each([
    [409, "DELETION_CONFLICT"],
    [409, "QUIZ_DELETION_PENDING"],
    [400, "TITLE_CONFIRMATION_REQUIRED"],
    [404, "QUIZ_NOT_FOUND"],
    [403, "ACCESS_DENIED"],
  ] as const)(
    "maps the %s %s failure envelope without inventing details",
    async (status, code) => {
      const { executor } = harness(async () =>
        jsonResponse(status, errorBody(status, code)),
      );
      const api = client({ executor });

      await expect(
        api.requestDeletion(quizId, {
          confirmTitle: "Network quiz",
          idempotencyKey: key,
        }),
      ).resolves.toEqual({
        ok: false,
        error: {
          kind: "api-error",
          error: {
            code,
            message: `${code} message`,
            status,
            path: `/api/quizzes/${quizId}/deletion-requests`,
          },
        },
      });
    },
  );

  it("reads the owned operation status from the exact route", async () => {
    const observed: string[] = [];
    const { executor } = harness(async (request) => {
      const built = request.createRequest();
      observed.push(String(built.target), built.method ?? "");
      return jsonResponse(200, {
        operationId,
        status: "BLOCKED_BY_RETENTION",
        createdAt: "2026-10-20T08:00:00Z",
        updatedAt: "2026-10-20T08:05:00Z",
        lastReason: "retention-window",
        stages: ["REQUESTED"],
        note: null,
      });
    });
    const api = client({ executor });

    await expect(api.getDeletionStatus(quizId, operationId)).resolves.toEqual({
      ok: true,
      value: {
        operationId,
        status: "BLOCKED_BY_RETENTION",
        createdAt: "2026-10-20T08:00:00Z",
        updatedAt: "2026-10-20T08:05:00Z",
        lastReason: "retention-window",
        stages: ["REQUESTED"],
        note: null,
      },
    });
    expect(observed).toEqual([
      `/api/quizzes/${quizId}/deletion-requests/${operationId}`,
      "GET",
    ]);
  });

  it("accepts COPY_EXPIRY_PENDING and rejects missing required note", async () => {
    let calls = 0;
    const { executor } = harness(async () => {
      calls += 1;
      return jsonResponse(
        200,
        calls === 1
          ? {
              operationId,
              status: "PRIMARY_PURGED",
              createdAt: "2026-10-20T08:00:00Z",
              updatedAt: "2026-10-20T09:00:00Z",
              lastReason: null,
              stages: ["REQUESTED", "QUIZ_FENCED"],
              note: "COPY_EXPIRY_PENDING",
            }
          : {
              operationId,
              status: "PRIMARY_PURGED",
              createdAt: "2026-10-20T08:00:00Z",
              updatedAt: "2026-10-20T09:00:00Z",
              lastReason: null,
              stages: ["REQUESTED", "QUIZ_FENCED"],
            },
      );
    });
    const api = client({ executor });

    const first = await api.getDeletionStatus(quizId, operationId);
    expect(first).toMatchObject({
      ok: true,
      value: { note: "COPY_EXPIRY_PENDING" },
    });
    const second = await api.getDeletionStatus(quizId, operationId);
    expect(second).toMatchObject({
      ok: false,
      error: { kind: "unexpected-response" },
    });
  });

  it("discovers the latest owned receipt with no-store and no operation id", async () => {
    const { executor } = harness(async (request) => {
      const built = request.createRequest();
      expect(built.target).toBe(
        `/api/quizzes/${quizId}/deletion-requests/latest`,
      );
      expect(built.method).toBe("GET");
      expect(built.cache).toBe("no-store");
      return jsonResponse(200, {
        operationId,
        status: "PRIMARY_PURGED",
        createdAt: "2026-10-20T08:00:00Z",
        updatedAt: "2026-10-20T09:00:00Z",
        lastReason: null,
        stages: ["REQUESTED", "PRIMARY_PURGED"],
        note: "COPY_EXPIRY_PENDING",
      });
    });
    await expect(
      client({ executor }).getLatestDeletionStatus(quizId),
    ).resolves.toMatchObject({
      ok: true,
      value: { operationId, note: "COPY_EXPIRY_PENDING" },
    });
  });

  it("rejects malformed successful payloads instead of trusting them", async () => {
    const { executor } = harness(async () =>
      jsonResponse(202, { operationId, status: "MADE_UP_STATUS" }),
    );
    const api = client({ executor });

    await expect(
      api.requestDeletion(quizId, {
        confirmTitle: "Network quiz",
        idempotencyKey: key,
      }),
    ).resolves.toEqual({
      ok: false,
      error: { kind: "unexpected-response", status: 202 },
    });
  });

  it("rejects a non-canonical idempotency key before any network call", async () => {
    const { execute, executor } = harness(async () =>
      jsonResponse(202, { operationId, status: "REQUESTED" }),
    );
    const api = client({ executor });

    for (const bad of ["short", "has spaces in it", "", "x".repeat(129)]) {
      await expect(
        api.requestDeletion(quizId, {
          confirmTitle: "Network quiz",
          idempotencyKey: bad,
        }),
      ).resolves.toEqual({ ok: false, error: { kind: "invalid-input" } });
    }
    expect(execute).not.toHaveBeenCalled();
  });

  it("refuses to run under a different session generation (session guard)", async () => {
    const { execute, executor } = harness(async () =>
      jsonResponse(202, { operationId, status: "REQUESTED" }),
    );
    let generation: number | null = 1;
    const api = client({ executor, generation: () => generation });

    // Bound to generation 1; switch before the call.
    generation = 2;
    await expect(
      api.requestDeletion(quizId, {
        confirmTitle: "Network quiz",
        idempotencyKey: key,
      }),
    ).resolves.toEqual({ ok: false, error: { kind: "session-changed" } });

    // Session gone entirely.
    generation = 1;
    const signedOut = client({ executor, generation: () => null });
    await expect(
      signedOut.requestDeletion(quizId, {
        confirmTitle: "Network quiz",
        idempotencyKey: key,
      }),
    ).resolves.toEqual({ ok: false, error: { kind: "session-changed" } });
    expect(execute).not.toHaveBeenCalled();
  });

  it("guards a generation flip that happens mid-flight", async () => {
    let generation: number | null = 1;
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => {
        generation = 2;
        request.createRequest();
        return {
          kind: "response" as const,
          response: jsonResponse(202, { operationId, status: "REQUESTED" }),
        };
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    const api = client({ executor, generation: () => generation });

    await expect(
      api.requestDeletion(quizId, {
        confirmTitle: "Network quiz",
        idempotencyKey: key,
      }),
    ).resolves.toEqual({ ok: false, error: { kind: "session-changed" } });
  });

  it("normalizes transport and authentication failures", async () => {
    const transportExecutor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => ({
        kind: "transport-error" as const,
        error: { kind: "network" as const },
      })),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    const authExecutor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => ({
        kind: "authentication-failure" as const,
        reason: "no-session" as const,
      })),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };

    await expect(
      client({ executor: transportExecutor }).requestDeletion(quizId, {
        confirmTitle: "Network quiz",
        idempotencyKey: key,
      }),
    ).resolves.toMatchObject({
      ok: false,
      error: { kind: "transport-error", error: { kind: "network" } },
    });
    await expect(
      client({ executor: authExecutor }).requestDeletion(quizId, {
        confirmTitle: "Network quiz",
        idempotencyKey: key,
      }),
    ).resolves.toEqual({
      ok: false,
      error: { kind: "authentication-failure", reason: "no-session" },
    });
  });

  it("rejects non-UUID identifiers locally", async () => {
    const { execute, executor } = harness(async () =>
      jsonResponse(202, { operationId, status: "REQUESTED" }),
    );
    const api = client({ executor });

    await expect(
      api.requestDeletion("not-a-uuid", {
        confirmTitle: "Network quiz",
        idempotencyKey: key,
      }),
    ).resolves.toEqual({ ok: false, error: { kind: "invalid-input" } });
    await expect(api.getDeletionStatus(quizId, "not-a-uuid")).resolves.toEqual({
      ok: false,
      error: { kind: "invalid-input" },
    });
    expect(execute).not.toHaveBeenCalled();
  });
});
