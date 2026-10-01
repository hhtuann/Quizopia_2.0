import { describe, expect, it, vi } from "vitest";
import type {
  AuthenticatedRequestExecutor,
  ReplayableAuthenticatedRequest,
} from "../../../lib/api/authenticated-request";
import type { HttpResponse } from "../../../lib/api/http-transport";
import { createQuizApiClient } from "./quiz-api-client";

const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";

function jsonResponse(status: number, value: unknown): HttpResponse {
  return {
    body: { kind: "json", value },
    headers: new Headers({ "content-type": "application/json" }),
    ok: status >= 200 && status < 300,
    status,
  };
}

function executorWith(
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

function draft(authoringSource: string) {
  return {
    quizId,
    title: "Quiz",
    description: "Description",
    authoringSource,
    createdAt: "2026-09-30T12:00:00Z",
    updatedAt: "2026-09-30T12:00:01Z",
  };
}

function libraryPage(nextCursor: string | null = "opaque-next") {
  return {
    items: [
      {
        quizId,
        title: "Quiz",
        description: "Description",
        createdAt: "2026-09-30T12:00:00Z",
        updatedAt: "2026-10-01T03:00:00Z",
        latestVersionNumber: 3,
      },
    ],
    nextCursor,
  };
}

describe("Quiz API client", () => {
  it("lists the owned quiz library through the exact cursor contract", async () => {
    const observed: string[] = [];
    const { executor } = executorWith(async (request) => {
      const built = request.createRequest();
      observed.push(String(built.target));
      expect(built.method).toBe("GET");
      return jsonResponse(200, libraryPage());
    });
    const client = createQuizApiClient({ authenticatedRequests: executor });

    await expect(client.listOwnedQuizzes()).resolves.toMatchObject({
      ok: true,
      value: {
        items: [{ quizId, latestVersionNumber: 3 }],
        nextCursor: "opaque-next",
      },
    });
    await expect(
      client.listOwnedQuizzes({ limit: 7, cursor: "opaque-cursor-token" }),
    ).resolves.toMatchObject({ ok: true });
    expect(observed).toEqual([
      "/api/quizzes",
      "/api/quizzes?limit=7&cursor=opaque-cursor-token",
    ]);
  });

  it("accepts an empty final library page with a null next cursor", async () => {
    const { executor } = executorWith(async () =>
      jsonResponse(200, { items: [], nextCursor: null }),
    );
    const client = createQuizApiClient({ authenticatedRequests: executor });

    await expect(client.listOwnedQuizzes()).resolves.toEqual({
      ok: true,
      value: { items: [], nextCursor: null },
    });
  });

  it.each([401, 403, 500])(
    "preserves backend %s API errors for library requests",
    async (status) => {
      const { executor } = executorWith(async () =>
        jsonResponse(status, {
          code: status === 401 ? "UNAUTHENTICATED" : "ACCESS_DENIED",
          message: `Library failed with ${status}`,
          status,
          path: "/api/quizzes",
        }),
      );
      const client = createQuizApiClient({ authenticatedRequests: executor });

      await expect(client.listOwnedQuizzes()).resolves.toMatchObject({
        ok: false,
        error: {
          kind: "api-error",
          error: { status, path: "/api/quizzes" },
        },
      });
    },
  );

  it("normalizes transport and authentication failures for library requests", async () => {
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
      createQuizApiClient({
        authenticatedRequests: transportExecutor,
      }).listOwnedQuizzes(),
    ).resolves.toMatchObject({
      ok: false,
      error: { kind: "transport-error", error: { kind: "network" } },
    });
    await expect(
      createQuizApiClient({
        authenticatedRequests: authExecutor,
      }).listOwnedQuizzes(),
    ).resolves.toEqual({
      ok: false,
      error: { kind: "authentication-failure", reason: "no-session" },
    });
  });

  it("rejects malformed successful library responses", async () => {
    const { executor } = executorWith(async () =>
      jsonResponse(200, {
        items: [{ quizId, title: "Quiz" }],
        nextCursor: "opaque-next",
      }),
    );
    const client = createQuizApiClient({ authenticatedRequests: executor });

    await expect(client.listOwnedQuizzes()).resolves.toEqual({
      ok: false,
      error: { kind: "unexpected-response", status: 200 },
    });
  });

  it("uses the authenticated executor and preserves source bytes in create/update bodies", async () => {
    const observed: Array<{ method?: string; target: string; body?: string }> =
      [];
    const exactSource = "\nCâu 1 [NUMERIC_FILL]: x\r\nĐáp án: 2.50\r\n\n";
    const { executor } = executorWith(async (request) => {
      const built = request.createRequest();
      observed.push({
        method: built.method,
        target: String(built.target),
        body: typeof built.body === "string" ? built.body : undefined,
      });
      return jsonResponse(
        built.method === "POST" ? 201 : 200,
        draft(exactSource),
      );
    });
    const client = createQuizApiClient({ authenticatedRequests: executor });
    const input = {
      title: "Quiz",
      description: "Description",
      authoringSource: exactSource,
    };

    expect((await client.createDraft(input)).ok).toBe(true);
    expect((await client.updateDraft(quizId, input)).ok).toBe(true);
    expect(observed).toHaveLength(2);
    expect(observed[0]?.target).toBe("/api/quizzes");
    expect(observed[1]?.target).toBe(`/api/quizzes/${quizId}/draft`);
    expect(JSON.parse(observed[0]?.body ?? "{}").authoringSource).toBe(
      exactSource,
    );
    expect(JSON.parse(observed[1]?.body ?? "{}").authoringSource).toBe(
      exactSource,
    );
  });

  it("reads a draft through the exact owned-draft route", async () => {
    const { execute, executor } = executorWith(async (request) => {
      const built = request.createRequest();
      expect(built.method).toBe("GET");
      expect(String(built.target)).toBe(`/api/quizzes/${quizId}/draft`);
      return jsonResponse(200, draft("source"));
    });
    const client = createQuizApiClient({ authenticatedRequests: executor });
    await expect(client.getDraft(quizId)).resolves.toMatchObject({ ok: true });
    expect(execute).toHaveBeenCalledTimes(1);
  });

  it("normalizes forbidden backend authorization without inventing frontend authorization", async () => {
    const { executor } = executorWith(async () =>
      jsonResponse(403, {
        code: "ACCESS_DENIED",
        message: "Access is denied",
        status: 403,
        path: `/api/quizzes/${quizId}/draft`,
      }),
    );
    const client = createQuizApiClient({ authenticatedRequests: executor });
    await expect(client.getDraft(quizId)).resolves.toEqual({
      ok: false,
      error: {
        kind: "api-error",
        error: {
          code: "ACCESS_DENIED",
          message: "Access is denied",
          status: 403,
          path: `/api/quizzes/${quizId}/draft`,
        },
      },
    });
  });

  it("maps authoritative publish validation diagnostics and accepts 200 version reuse", async () => {
    let publishCalls = 0;
    const { executor } = executorWith(async () => {
      publishCalls += 1;
      if (publishCalls === 1) {
        return jsonResponse(400, {
          code: "QUIZ_MARKDOWN_INVALID",
          message: "Quiz Markdown validation failed.",
          status: 400,
          path: `/api/quizzes/${quizId}/versions`,
          traceId: "abc123",
          errors: [
            {
              code: "NUMERIC_ANSWER_INVALID",
              questionNumber: 1,
              line: 2,
              column: 9,
              message: "Invalid numeric answer",
            },
          ],
        });
      }
      return jsonResponse(200, {
        id: "e7b14962-3a2e-4d2d-926a-3b36ea90c199",
        quizId,
        versionNumber: 1,
        createdAt: "2026-09-30T12:30:00Z",
      });
    });
    const client = createQuizApiClient({ authenticatedRequests: executor });

    await expect(client.publishDraft(quizId)).resolves.toMatchObject({
      ok: false,
      error: {
        kind: "validation-error",
        errors: [{ code: "NUMERIC_ANSWER_INVALID", line: 2, column: 9 }],
      },
    });
    await expect(client.publishDraft(quizId)).resolves.toMatchObject({
      ok: true,
      value: { created: false, version: { quizId, versionNumber: 1 } },
    });
  });
});
