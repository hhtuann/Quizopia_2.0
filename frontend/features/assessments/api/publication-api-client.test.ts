import { afterEach, describe, expect, it, vi } from "vitest";
import { createAuthenticatedUser } from "../../auth/model/authenticated-user";
import { createAccessTokenVault } from "../../auth/session/access-token-vault";
import { createRefreshCoordinator } from "../../auth/session/refresh-coordinator";
import type { RefreshOperation } from "../../auth/session/refresh-result";
import { createSessionRuntime } from "../../auth/session/session-runtime";
import {
  createAuthenticatedRequestExecutor,
  type AuthenticatedRequestExecutor,
  type AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import {
  createFetchHttpTransport,
  type HttpRequest,
} from "../../../lib/api/http-transport";
import type {
  Configuration,
  OwnerView,
} from "./publication-contract.generated";
import {
  createPublicationApiClient,
  isUncertain,
  publicationFailureMessage,
  PUBLICATION_REQUEST_TIMEOUT,
} from "./publication-api-client";

const id = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const secondId = "f7b14962-3a2e-4d2d-926a-3b36ea90c199";
const key = "publication_operation_123456";
const configuration: Configuration = {
  title: "Network assessment",
  quizId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  quizVersionId: "9bd4c564-3c27-4e6d-91aa-a004334aa8f7",
  versionNumber: 3,
  audience: "PUBLIC",
  startAt: "2026-10-16T08:00:00Z",
  endAt: "2026-10-16T09:00:00Z",
  durationMinutes: 40,
  monitoringChoice: "DISABLED",
  maxAttempts: 2,
};
const owner: OwnerView = {
  publicationId: id,
  configuration,
  state: "DRAFT",
  revision: 0,
  createdAt: "2026-10-16T07:00:00Z",
  openedAt: null,
  privateLocator: "A".repeat(43),
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

function response(status: number, value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "content-type": "application/json",
      "cache-control": "no-store",
    },
  });
}

function session(accessToken = "test-access", userId = id) {
  return {
    accessToken,
    user: createAuthenticatedUser({
      id: userId,
      username: "teacher",
      email: "teacher@gmail.com",
      roles: ["TEACHER"],
    }),
  };
}

function harness(
  fetchImplementation: typeof fetch,
  refreshOperation: RefreshOperation = vi.fn(async () => ({
    status: "no-session" as const,
  })),
) {
  const vault = createAccessTokenVault();
  const runtime = createSessionRuntime(vault);
  runtime.establishAuthenticatedSession(session());
  const executor = createAuthenticatedRequestExecutor({
    accessTokenVault: vault,
    refreshCoordinator: createRefreshCoordinator(runtime, refreshOperation),
    sessionRuntime: runtime,
    transport: createFetchHttpTransport(fetchImplementation),
  });
  const client = createPublicationApiClient({
    authenticatedRequests: executor,
    getSessionGeneration: () =>
      vault.read() === null ? null : vault.readSnapshot().sessionGeneration,
    apiBaseUrl: "http://localhost:8080",
  });
  return { client, runtime, vault, refreshOperation };
}

function controlledExecutor() {
  const execute = vi.fn<AuthenticatedRequestExecutor["execute"]>();
  const executor: AuthenticatedRequestExecutor = {
    execute,
    executeOnce: vi.fn(),
    executeOnceWithAccessToken: vi.fn(),
  };
  let generation: number | null = 1;
  const client = createPublicationApiClient({
    authenticatedRequests: executor,
    getSessionGeneration: () => generation,
    apiBaseUrl: "http://localhost:8080",
  });
  return {
    client,
    execute,
    switchGeneration: (next: number | null) => {
      generation = next;
    },
  };
}

function result(status: number, value: unknown): AuthenticatedRequestResult {
  return {
    kind: "response",
    response: {
      status,
      ok: status >= 200 && status < 300,
      headers: new Headers(),
      body: { kind: "json", value },
    },
  };
}

afterEach(() => {
  vi.useRealTimers();
});

describe("Publication Gateway requests through the production executor", () => {
  it("lists only safe owner metadata with encoded server filters and opaque cursors", async () => {
    const summary = {
      publicationId: id,
      title: configuration.title,
      audience: configuration.audience,
      state: owner.state,
      startAt: configuration.startAt,
      endAt: configuration.endAt,
      createdAt: owner.createdAt,
      revision: 0,
      quizId: configuration.quizId,
      quizVersionId: configuration.quizVersionId,
      versionNumber: 3,
    };
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        response(200, { items: [summary], nextCursor: "opaque+/=" }),
      );
    const { client } = harness(fetchMock);
    expect(
      await client.list({
        search: "  network & quiz  ",
        state: "DRAFT",
        cursor: "opaque+/=",
      }),
    ).toEqual({
      ok: true,
      value: { items: [summary], nextCursor: "opaque+/=" },
    });
    const url = new URL(String(fetchMock.mock.calls[0]![0]));
    expect(url.pathname).toBe("/api/assessments/publications");
    expect(Object.fromEntries(url.searchParams)).toEqual({
      limit: "20",
      search: "network & quiz",
      state: "DRAFT",
      cursor: "opaque+/=",
    });
    fetchMock.mockResolvedValue(
      response(200, {
        items: [{ ...summary, privateLocator: owner.privateLocator }],
        nextCursor: null,
      }),
    );
    expect(await client.list()).toMatchObject({
      ok: false,
      error: { kind: "invalid-response" },
    });
    const before = fetchMock.mock.calls.length;
    expect(await client.list({ limit: 101 })).toMatchObject({
      ok: false,
      error: { kind: "invalid-input" },
    });
    expect(fetchMock).toHaveBeenCalledTimes(before);
  });
  it("sends the immutable source triple, exact configuration and explicit create operation identity", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(response(201, owner));
    const { client } = harness(fetchMock);
    await expect(client.create(configuration, key)).resolves.toEqual({
      ok: true,
      value: owner,
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [target, init] = fetchMock.mock.calls[0]!;
    expect(String(target)).toBe(
      "http://localhost:8080/api/assessments/publications",
    );
    expect(init?.method).toBe("POST");
    expect(init?.body).toBe(JSON.stringify(configuration));
    expect(new Headers(init?.headers).get("Idempotency-Key")).toBe(key);
    expect(new Headers(init?.headers).get("authorization")).toBe(
      "Bearer test-access",
    );
    expect(new Headers(init?.headers).has("X-Quizopia-User-Evidence")).toBe(
      false,
    );
    expect(init?.signal).toBeInstanceOf(AbortSignal);
  });

  it("replays a create exactly once after genuine 401 using the same key and serialized body", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(401, { code: "UNAUTHENTICATED" }))
      .mockResolvedValueOnce(response(201, owner));
    const refresh = vi.fn<RefreshOperation>(async () => ({
      status: "refreshed",
      session: session("renewed-access"),
    }));
    const { client, vault } = harness(fetchMock, refresh);
    await expect(client.create(configuration, key)).resolves.toMatchObject({
      ok: true,
    });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const [, init] of fetchMock.mock.calls) {
      expect(init?.body).toBe(JSON.stringify(configuration));
      expect(new Headers(init?.headers).get("Idempotency-Key")).toBe(key);
    }
    expect(
      new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get("authorization"),
    ).toBe("Bearer renewed-access");
    expect(vault.read()).toBe("renewed-access");
  });

  it("uses only documented detail, full replacement, OPEN and extension routes", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockImplementation(async () => response(200, owner));
    const { client } = harness(fetchMock);
    await client.get(id);
    await client.update(id, { expectedRevision: 0, configuration });
    await client.open(id, { expectedRevision: 0 });
    await client.extend(id, {
      expectedRevision: 1,
      endAt: "2026-10-16T10:00:00Z",
      durationMinutes: 45,
    });
    expect(
      fetchMock.mock.calls.map(([target, init]) => [
        String(target),
        init?.method,
        init?.body,
      ]),
    ).toEqual([
      [
        `http://localhost:8080/api/assessments/publications/${id}`,
        "GET",
        undefined,
      ],
      [
        `http://localhost:8080/api/assessments/publications/${id}`,
        "PUT",
        JSON.stringify({ expectedRevision: 0, configuration }),
      ],
      [
        `http://localhost:8080/api/assessments/publications/${id}/open`,
        "POST",
        '{"expectedRevision":0}',
      ],
      [
        `http://localhost:8080/api/assessments/publications/${id}/schedule`,
        "PATCH",
        JSON.stringify({
          expectedRevision: 1,
          endAt: "2026-10-16T10:00:00Z",
          durationMinutes: 45,
        }),
      ],
    ]);
    for (const [, init] of fetchMock.mock.calls)
      expect(new Headers(init?.headers).has("Idempotency-Key")).toBe(false);
  });

  it("accepts the minimal CLOSE acknowledgement without fabricating full owner metadata", async () => {
    const acknowledgement = {
      publicationId: id,
      state: "CLOSED",
      revision: 2,
      closedAt: "2026-10-16T08:30:00Z",
    };
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(response(200, acknowledgement));
    const { client } = harness(fetchMock);
    await expect(
      client.close(id, { expectedRevision: 1 }, key),
    ).resolves.toEqual({ ok: true, value: acknowledgement });
    const [target, init] = fetchMock.mock.calls[0]!;
    expect(String(target)).toBe(
      `http://localhost:8080/api/assessments/publications/${id}/close`,
    );
    expect(init?.body).toBe('{"expectedRevision":1}');
    expect(new Headers(init?.headers).get("Idempotency-Key")).toBe(key);
  });

  it.each([
    [400, "INVALID_REQUEST"],
    [403, "ACCESS_DENIED"],
    [404, "PUBLICATION_NOT_FOUND"],
    [409, "PUBLICATION_CONFLICT"],
    [409, "ACTIVE_ATTEMPTS"],
    [409, "CLOSURE_PENDING"],
    [409, "REVISION_CONFLICT"],
    [503, "AUTHORITY_UNAVAILABLE"],
    [503, "DEPENDENCY_UNAVAILABLE"],
    [503, "PERSISTENCE_UNAVAILABLE"],
  ])(
    "preserves sanitized code-only HTTP %i %s without refresh or logout",
    async (status, code) => {
      const fetchMock = vi
        .fn<typeof fetch>()
        .mockResolvedValue(response(status as number, { code }));
      const refresh = vi.fn<RefreshOperation>();
      const { client, runtime, vault } = harness(fetchMock, refresh);
      await expect(client.get(id)).resolves.toEqual({
        ok: false,
        error: { kind: "api", status, code },
      });
      expect(refresh).not.toHaveBeenCalled();
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(runtime.getSnapshot().status).toBe("authenticated");
      expect(vault.read()).toBe("test-access");
    },
  );

  it("keeps genuine invalid refresh distinct from unavailable refresh", async () => {
    const denied = harness(
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(response(401, { code: "UNAUTHENTICATED" })),
    );
    await expect(denied.client.get(id)).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
    expect(denied.runtime.getSnapshot().status).toBe("session-expired");
    const unavailable = harness(
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(response(401, { code: "UNAUTHENTICATED" })),
      vi.fn(async () => ({ status: "transient-failure" as const })),
    );
    await expect(unavailable.client.get(id)).resolves.toEqual({
      ok: false,
      error: { kind: "unavailable", reason: "refresh" },
    });
    expect(unavailable.runtime.getSnapshot().status).toBe("authenticated");
  });

  it("rejects successful stale account responses and prevents 401 replay after account switching", async () => {
    const pending = deferred<Response>();
    const fetchMock = vi.fn<typeof fetch>().mockReturnValue(pending.promise);
    const refresh = vi.fn<RefreshOperation>();
    const { client, runtime } = harness(fetchMock, refresh);
    const request = client.get(id);
    runtime.clearLocalSession();
    runtime.establishAuthenticatedSession(
      session("new-account-token", secondId),
    );
    pending.resolve(response(200, owner));
    await expect(request).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
    expect(runtime.getSnapshot()).toMatchObject({ user: { id: secondId } });
    await expect(client.open(id, { expectedRevision: 0 })).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const later = deferred<Response>();
    const laterFetch = vi.fn<typeof fetch>().mockReturnValue(later.promise);
    const second = harness(laterFetch, refresh);
    const unauthorized = second.client.open(id, { expectedRevision: 0 });
    second.runtime.clearLocalSession();
    second.runtime.establishAuthenticatedSession(
      session("third-account-token"),
    );
    later.resolve(response(401, { code: "UNAUTHENTICATED" }));
    await expect(unauthorized).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
    expect(refresh).not.toHaveBeenCalled();
    expect(laterFetch).toHaveBeenCalledTimes(1);
  });
});

describe("Publication boundary failures and cancellation", () => {
  it.each([
    [401, "UNAUTHENTICATED"],
    [409, "FUTURE_SANITIZED_CODE"],
  ])(
    "preserves code-only HTTP %i when supplied by the executor",
    async (status, code) => {
      const { client, execute } = controlledExecutor();
      execute.mockResolvedValue(result(status as number, { code }));
      await expect(client.get(id)).resolves.toEqual({
        ok: false,
        error: { kind: "api", status, code },
      });
    },
  );

  it("distinguishes network failure, malformed JSON and unsupported success status", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockRejectedValueOnce(new TypeError("offline"))
      .mockResolvedValueOnce(
        new Response("{", {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      )
      .mockResolvedValueOnce(response(202, owner));
    const { client, runtime } = harness(fetchMock);
    await expect(client.get(id)).resolves.toEqual({
      ok: false,
      error: { kind: "unavailable", reason: "network" },
    });
    await expect(client.get(id)).resolves.toEqual({
      ok: false,
      error: { kind: "invalid-response" },
    });
    await expect(client.get(id)).resolves.toEqual({
      ok: false,
      error: { kind: "invalid-response", status: 202 },
    });
    expect(runtime.getSnapshot().status).toBe("authenticated");
  });

  it("rejects unauthenticated and already-aborted operations before touching the executor", async () => {
    const { client, execute, switchGeneration } = controlledExecutor();
    switchGeneration(null);
    await expect(client.get(id)).resolves.toMatchObject({
      ok: false,
      error: { kind: "session" },
    });
    switchGeneration(1);
    await expect(client.get(id, AbortSignal.abort())).resolves.toMatchObject({
      ok: false,
      error: { kind: "aborted" },
    });
    expect(execute).not.toHaveBeenCalled();
  });

  it("rejects malformed UUIDs, unsafe revisions and invalid operation keys before network", async () => {
    const { client, execute } = controlledExecutor();
    for (const operation of [
      client.get("latest"),
      client.create(configuration, "short"),
      client.open(id, { expectedRevision: Number.MAX_SAFE_INTEGER + 1 }),
      client.update(id, { expectedRevision: -1, configuration }),
      client.extend(id, {
        expectedRevision: 0,
        endAt: "yesterday",
        durationMinutes: 40,
      }),
      client.close(id, { expectedRevision: 0 }, "bad key"),
    ]) {
      await expect(operation).resolves.toEqual({
        ok: false,
        error: { kind: "invalid-input" },
      });
    }
    expect(execute).not.toHaveBeenCalled();
  });

  it.each([
    { ...owner, publicationId: secondId },
    { ...owner, revision: Number.MAX_SAFE_INTEGER + 1 },
    { ...owner, questions: [{ correct: true }] },
    { ...owner, ownerId: id },
  ])("fails closed on invalid owner metadata", async (value) => {
    const { client, execute } = controlledExecutor();
    execute.mockResolvedValue(result(200, value));
    await expect(client.get(id)).resolves.toMatchObject({
      ok: false,
      error: { kind: "invalid-response" },
    });
  });

  it("rejects wrong-resource CLOSE acknowledgements and malformed error projections", async () => {
    const { client, execute } = controlledExecutor();
    execute
      .mockResolvedValueOnce(
        result(200, {
          publicationId: secondId,
          state: "CLOSED",
          revision: 2,
          closedAt: "2026-10-16T08:30:00Z",
        }),
      )
      .mockResolvedValueOnce(
        result(503, { code: "AUTHORITY_UNAVAILABLE", token: "redacted" }),
      );
    await expect(
      client.close(id, { expectedRevision: 1 }, key),
    ).resolves.toMatchObject({
      ok: false,
      error: { kind: "invalid-response" },
    });
    await expect(client.get(id)).resolves.toEqual({
      ok: false,
      error: { kind: "invalid-response", status: 503 },
    });
  });

  it("requires the exact 201 create status rather than accepting undocumented 200", async () => {
    const { client, execute } = controlledExecutor();
    execute.mockResolvedValue(result(200, owner));
    await expect(client.create(configuration, key)).resolves.toEqual({
      ok: false,
      error: { kind: "invalid-response", status: 200 },
    });
  });

  it("bounds an abort-ignoring fetch at exactly 15 seconds and ignores late success", async () => {
    vi.useFakeTimers();
    const pending = deferred<Response>();
    const fetchMock = vi.fn<typeof fetch>().mockReturnValue(pending.promise);
    const { client, runtime } = harness(fetchMock);
    let settled = false;
    const operation = client.create(configuration, key).then((value) => {
      settled = true;
      return value;
    });
    const signal = fetchMock.mock.calls[0]?.[1]?.signal;
    expect(signal?.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(PUBLICATION_REQUEST_TIMEOUT - 1);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await expect(operation).resolves.toEqual({
      ok: false,
      error: { kind: "unavailable", reason: "timeout" },
    });
    expect(signal?.aborted).toBe(true);
    pending.resolve(response(201, owner));
    await Promise.resolve();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(runtime.getSnapshot().status).toBe("authenticated");
    fetchMock.mockResolvedValueOnce(response(200, owner));
    await expect(client.get(id)).resolves.toMatchObject({ ok: true });
  });

  it("propagates caller abort to actual fetch and never replays a late callback", async () => {
    const pending = deferred<Response>();
    const fetchMock = vi.fn<typeof fetch>().mockReturnValue(pending.promise);
    const { client } = harness(fetchMock);
    const controller = new AbortController();
    const operation = client.get(id, controller.signal);
    const signal = fetchMock.mock.calls[0]?.[1]?.signal;
    controller.abort();
    await expect(operation).resolves.toEqual({
      ok: false,
      error: { kind: "aborted" },
    });
    expect(signal?.aborted).toBe(true);
    pending.resolve(response(401, { code: "UNAUTHENTICATED" }));
    await Promise.resolve();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("guards request factory replay after generation change even with a noncooperating executor", async () => {
    const { client, execute, switchGeneration } = controlledExecutor();
    const pending = deferred<AuthenticatedRequestResult>();
    let createRequest!: () => Omit<HttpRequest, "signal">;
    execute.mockImplementation((request) => {
      createRequest = request.createRequest;
      createRequest();
      return pending.promise;
    });
    const operation = client.open(id, { expectedRevision: 0 });
    switchGeneration(2);
    expect(() => createRequest()).toThrow("Obsolete Publication operation");
    pending.resolve(result(200, owner));
    await expect(operation).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
  });

  it("classifies uncertain mutation outcomes and uses truthful sanitized messages", () => {
    expect(isUncertain({ kind: "unavailable", reason: "network" })).toBe(true);
    expect(isUncertain({ kind: "aborted" })).toBe(true);
    expect(
      isUncertain({ kind: "api", status: 503, code: "AUTHORITY_UNAVAILABLE" }),
    ).toBe(true);
    expect(
      isUncertain({ kind: "api", status: 409, code: "ACTIVE_ATTEMPTS" }),
    ).toBe(false);
    expect(
      publicationFailureMessage({
        kind: "api",
        status: 409,
        code: "ACTIVE_ATTEMPTS",
      }),
    ).toContain("there is no force-close");
    expect(
      publicationFailureMessage({
        kind: "api",
        status: 503,
        code: "AUTHORITY_UNAVAILABLE",
      }),
    ).toContain("You are still signed in");
    expect(
      publicationFailureMessage({
        kind: "api",
        status: 409,
        code: "UNKNOWN_NEW_CODE",
      }),
    ).not.toContain("UNKNOWN_NEW_CODE");
  });
});
