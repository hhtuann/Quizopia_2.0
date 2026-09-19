import { describe, expect, it, vi } from "vitest";
import { createAuthenticatedUser } from "../../features/auth/model/authenticated-user";
import { createAccessTokenVault } from "../../features/auth/session/access-token-vault";
import { createRefreshCoordinator } from "../../features/auth/session/refresh-coordinator";
import type {
  RefreshOperation,
  RefreshResult,
} from "../../features/auth/session/refresh-result";
import { createSessionRuntime } from "../../features/auth/session/session-runtime";
import {
  createAuthenticatedRequestExecutor,
  type ReplayableAuthenticatedRequest,
} from "./authenticated-request";
import {
  createFetchHttpTransport,
  type HttpRequest,
  type HttpTransport,
  type HttpTransportResult,
} from "./http-transport";

const userId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

function session(accessToken: string) {
  return {
    accessToken,
    user: createAuthenticatedUser({ userId, roles: ["STUDENT"] }),
  };
}

function response(status: number): HttpTransportResult {
  return {
    kind: "response",
    response: {
      body: { kind: "empty" },
      headers: new Headers(),
      ok: status >= 200 && status < 300,
      status,
    },
  };
}

function authorization(request: HttpRequest): string | null {
  return new Headers(request.headers).get("authorization");
}

function replayableRequest(
  name: string,
  signal?: AbortSignal,
): ReplayableAuthenticatedRequest & {
  createRequest: ReturnType<typeof vi.fn>;
} {
  return {
    createRequest: vi.fn(() => ({
      method: "POST",
      target: `https://quizopia.test/resources/${name}`,
    })),
    signal,
  };
}

function authenticatedHarness(
  transport: HttpTransport,
  refreshOperation: RefreshOperation,
) {
  const accessTokenVault = createAccessTokenVault();
  const sessionRuntime = createSessionRuntime(accessTokenVault);
  sessionRuntime.establishAuthenticatedSession(session("initial-access-token"));
  const refreshCoordinator = createRefreshCoordinator(
    sessionRuntime,
    refreshOperation,
  );
  const executor = createAuthenticatedRequestExecutor({
    accessTokenVault,
    refreshCoordinator,
    sessionRuntime,
    transport,
  });
  return { accessTokenVault, executor, sessionRuntime };
}

describe("authenticated request executor", () => {
  it("fails before transport when no access token is available", async () => {
    const accessTokenVault = createAccessTokenVault();
    const sessionRuntime = createSessionRuntime(accessTokenVault);
    const execute = vi.fn(async () => response(200));
    const refreshOperation = vi.fn<RefreshOperation>();
    const executor = createAuthenticatedRequestExecutor({
      accessTokenVault,
      refreshCoordinator: createRefreshCoordinator(
        sessionRuntime,
        refreshOperation,
      ),
      sessionRuntime,
      transport: { execute },
    });
    const request = replayableRequest("no-token");

    await expect(executor.execute(request)).resolves.toEqual({
      kind: "authentication-failure",
      reason: "no-access-token",
    });
    expect(request.createRequest).not.toHaveBeenCalled();
    expect(execute).not.toHaveBeenCalled();
    expect(refreshOperation).not.toHaveBeenCalled();
  });

  it("attaches the current bearer token without changing the request target", async () => {
    const observedRequests: HttpRequest[] = [];
    const transport: HttpTransport = {
      execute: vi.fn(async (request) => {
        observedRequests.push(request);
        return response(200);
      }),
    };
    const refreshOperation = vi.fn<RefreshOperation>();
    const { accessTokenVault, executor } = authenticatedHarness(
      transport,
      refreshOperation,
    );

    await executor.execute(replayableRequest("first"));
    accessTokenVault.replace("replacement-access-token");
    await executor.execute(replayableRequest("second"));

    expect(observedRequests).toHaveLength(2);
    expect(authorization(observedRequests[0]!)).toBe(
      "Bearer initial-access-token",
    );
    expect(authorization(observedRequests[1]!)).toBe(
      "Bearer replacement-access-token",
    );
    expect(String(observedRequests[0]!.target)).toBe(
      "https://quizopia.test/resources/first",
    );
    expect(String(observedRequests[0]!.target)).not.toContain(
      "initial-access-token",
    );
    expect(refreshOperation).not.toHaveBeenCalled();
  });

  it("rejects a caller Authorization header instead of overwriting it", async () => {
    const execute = vi.fn(async () => response(200));
    const refreshOperation = vi.fn<RefreshOperation>();
    const { executor } = authenticatedHarness({ execute }, refreshOperation);

    const result = await executor.execute({
      createRequest: () => ({
        headers: { Authorization: "Caller supplied value" },
        target: "https://quizopia.test/resources/conflict",
      }),
    });

    expect(result).toEqual({
      kind: "authentication-failure",
      reason: "authorization-header-conflict",
    });
    expect(execute).not.toHaveBeenCalled();
    expect(refreshOperation).not.toHaveBeenCalled();
  });

  it("coordinates three concurrent 401s through one refresh and retries each once", async () => {
    const refreshAttempt = deferred<RefreshResult>();
    const refreshOperation = vi.fn<RefreshOperation>();
    refreshOperation.mockReturnValue(refreshAttempt.promise);
    const attempts = new Map<string, string[]>();
    const execute = vi.fn(
      async (request: HttpRequest): Promise<HttpTransportResult> => {
        const target = String(request.target);
        const requestAttempts = attempts.get(target) ?? [];
        requestAttempts.push(authorization(request) ?? "missing");
        attempts.set(target, requestAttempts);
        return authorization(request) === "Bearer initial-access-token"
          ? response(401)
          : response(200);
      },
    );
    const { accessTokenVault, executor } = authenticatedHarness(
      { execute },
      refreshOperation,
    );
    const requests = ["a", "b", "c"].map((name) => replayableRequest(name));

    const pendingResults = requests.map((request) => executor.execute(request));
    await vi.waitFor(() => {
      expect(refreshOperation).toHaveBeenCalledTimes(1);
    });
    refreshAttempt.resolve({
      status: "refreshed",
      session: session("replacement-access-token"),
    });
    const results = await Promise.all(pendingResults);

    expect(refreshOperation).toHaveBeenCalledTimes(1);
    expect(execute).toHaveBeenCalledTimes(6);
    expect(accessTokenVault.read()).toBe("replacement-access-token");
    expect(results.every((result) => result.kind === "response")).toBe(true);
    for (const request of requests) {
      expect(request.createRequest).toHaveBeenCalledTimes(2);
    }
    for (const requestAttempts of attempts.values()) {
      expect(requestAttempts).toEqual([
        "Bearer initial-access-token",
        "Bearer replacement-access-token",
      ]);
    }
  });

  it("creates a fresh replayable request body for the single retry", async () => {
    const observedBodies: BodyInit[] = [];
    const execute = vi.fn(
      async (request: HttpRequest): Promise<HttpTransportResult> => {
        if (request.body !== undefined && request.body !== null) {
          observedBodies.push(request.body);
        }
        return authorization(request) === "Bearer initial-access-token"
          ? response(401)
          : response(200);
      },
    );
    const refreshOperation = vi.fn<RefreshOperation>();
    refreshOperation.mockResolvedValue({
      status: "refreshed",
      session: session("replacement-access-token"),
    });
    const { executor } = authenticatedHarness({ execute }, refreshOperation);
    const request: ReplayableAuthenticatedRequest = {
      createRequest: vi.fn(() => ({
        body: new Blob(["replayable body"]),
        method: "POST",
        target: "https://quizopia.test/resources/replayable",
      })),
    };

    await expect(executor.execute(request)).resolves.toMatchObject({
      kind: "response",
      response: { status: 200 },
    });

    expect(request.createRequest).toHaveBeenCalledTimes(2);
    expect(observedBodies).toHaveLength(2);
    expect(observedBodies[0]).not.toBe(observedBodies[1]);
    expect(refreshOperation).toHaveBeenCalledTimes(1);
    expect(execute).toHaveBeenCalledTimes(2);
  });

  it("retries a delayed stale 401 with the current token without refreshing again", async () => {
    const delayedUnauthorized = deferred<HttpTransportResult>();
    const refreshOperation = vi.fn<RefreshOperation>();
    refreshOperation.mockResolvedValue({
      status: "refreshed",
      session: session("replacement-access-token"),
    });
    const attempts = new Map<string, string[]>();
    const execute = vi.fn(
      async (request: HttpRequest): Promise<HttpTransportResult> => {
        const name = String(request.target).endsWith("/a") ? "a" : "b";
        const requestAttempts = attempts.get(name) ?? [];
        requestAttempts.push(authorization(request) ?? "missing");
        attempts.set(name, requestAttempts);

        if (
          name === "b" &&
          authorization(request) === "Bearer initial-access-token"
        ) {
          return delayedUnauthorized.promise;
        }

        return authorization(request) === "Bearer initial-access-token"
          ? response(401)
          : response(200);
      },
    );
    const { executor } = authenticatedHarness({ execute }, refreshOperation);

    const first = executor.execute(replayableRequest("a"));
    const delayed = executor.execute(replayableRequest("b"));
    await expect(first).resolves.toMatchObject({
      kind: "response",
      response: { status: 200 },
    });
    expect(refreshOperation).toHaveBeenCalledTimes(1);

    delayedUnauthorized.resolve(response(401));
    await expect(delayed).resolves.toMatchObject({
      kind: "response",
      response: { status: 200 },
    });

    expect(refreshOperation).toHaveBeenCalledTimes(1);
    expect(attempts.get("b")).toEqual([
      "Bearer initial-access-token",
      "Bearer replacement-access-token",
    ]);
  });

  it("returns a typed failure and expires locally when the one retry is also unauthorized", async () => {
    const execute = vi.fn(async () => response(401));
    const refreshOperation = vi.fn<RefreshOperation>();
    refreshOperation.mockResolvedValue({
      status: "refreshed",
      session: session("replacement-access-token"),
    });
    const { accessTokenVault, executor, sessionRuntime } = authenticatedHarness(
      { execute },
      refreshOperation,
    );

    const result = await executor.execute(replayableRequest("unauthorized"));

    expect(result).toMatchObject({
      kind: "authentication-failure",
      reason: "unauthorized-after-retry",
      response: { status: 401 },
    });
    expect(refreshOperation).toHaveBeenCalledTimes(1);
    expect(execute).toHaveBeenCalledTimes(2);
    expect(sessionRuntime.getSnapshot()).toEqual({ status: "session-expired" });
    expect(accessTokenVault.read()).toBeNull();
  });

  it("expires all concurrent callers without retry when refresh reports no session", async () => {
    const refreshAttempt = deferred<RefreshResult>();
    const refreshOperation = vi.fn<RefreshOperation>();
    refreshOperation.mockReturnValue(refreshAttempt.promise);
    const execute = vi.fn(async () => response(401));
    const { accessTokenVault, executor, sessionRuntime } = authenticatedHarness(
      { execute },
      refreshOperation,
    );
    const requests = ["a", "b", "c"].map((name) => replayableRequest(name));

    const pendingResults = requests.map((request) => executor.execute(request));
    await vi.waitFor(() => {
      expect(refreshOperation).toHaveBeenCalledTimes(1);
    });
    refreshAttempt.resolve({ status: "no-session" });
    const results = await Promise.all(pendingResults);

    expect(refreshOperation).toHaveBeenCalledTimes(1);
    expect(execute).toHaveBeenCalledTimes(3);
    expect(
      results.every(
        (result) =>
          result.kind === "authentication-failure" &&
          result.reason === "no-session",
      ),
    ).toBe(true);
    expect(sessionRuntime.getSnapshot()).toEqual({ status: "session-expired" });
    expect(accessTokenVault.read()).toBeNull();
    for (const request of requests) {
      expect(request.createRequest).toHaveBeenCalledTimes(1);
    }
  });

  it("returns a recoverable failure without expiring on transient refresh failure", async () => {
    const refreshAttempt = deferred<RefreshResult>();
    const refreshOperation = vi.fn<RefreshOperation>();
    refreshOperation.mockReturnValue(refreshAttempt.promise);
    const execute = vi.fn(async () => response(401));
    const { accessTokenVault, executor, sessionRuntime } = authenticatedHarness(
      { execute },
      refreshOperation,
    );
    const failureCause = new Error("Temporary refresh failure");
    const requests = ["a", "b", "c"].map((name) => replayableRequest(name));

    const pendingResults = requests.map((request) => executor.execute(request));
    await vi.waitFor(() => {
      expect(refreshOperation).toHaveBeenCalledTimes(1);
    });
    refreshAttempt.resolve({
      status: "transient-failure",
      cause: failureCause,
    });
    const results = await Promise.all(pendingResults);

    expect(refreshOperation).toHaveBeenCalledTimes(1);
    expect(execute).toHaveBeenCalledTimes(3);
    expect(
      results.every(
        (result) =>
          result.kind === "refresh-failure" && result.cause === failureCause,
      ),
    ).toBe(true);
    expect(sessionRuntime.getSnapshot().status).toBe("authenticated");
    expect(accessTokenVault.read()).toBe("initial-access-token");
    for (const request of requests) {
      expect(request.createRequest).toHaveBeenCalledTimes(1);
    }
  });

  it("does not refresh, retry, or mutate the session when fetch aborts in flight", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    fetchMock.mockImplementation(
      (_input, init) =>
        new Promise<Response>((_resolve, reject) => {
          const signal = init?.signal;
          if (signal === null || signal === undefined) {
            reject(new Error("Expected an AbortSignal in this test."));
            return;
          }

          if (signal.aborted) {
            reject(signal.reason);
            return;
          }

          signal.addEventListener("abort", () => reject(signal.reason), {
            once: true,
          });
        }),
    );
    const refreshOperation = vi.fn<RefreshOperation>();
    const { accessTokenVault, executor, sessionRuntime } = authenticatedHarness(
      createFetchHttpTransport(fetchMock),
      refreshOperation,
    );
    const controller = new AbortController();
    const request = replayableRequest("abort-during-fetch", controller.signal);
    const sessionBeforeAbort = sessionRuntime.getSnapshot();

    const pendingResult = executor.execute(request);
    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });
    controller.abort("Caller canceled");

    await expect(pendingResult).resolves.toEqual({
      kind: "transport-error",
      error: { kind: "aborted", cause: "Caller canceled" },
    });
    expect(request.createRequest).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(refreshOperation).not.toHaveBeenCalled();
    expect(sessionRuntime.getSnapshot()).toBe(sessionBeforeAbort);
    expect(accessTokenVault.read()).toBe("initial-access-token");
  });

  it("does not retry an aborted caller or cancel another caller's shared refresh", async () => {
    const refreshAttempt = deferred<RefreshResult>();
    const refreshOperation = vi.fn<RefreshOperation>();
    refreshOperation.mockReturnValue(refreshAttempt.promise);
    const execute = vi.fn(
      async (request: HttpRequest): Promise<HttpTransportResult> =>
        authorization(request) === "Bearer initial-access-token"
          ? response(401)
          : response(200),
    );
    const { executor } = authenticatedHarness({ execute }, refreshOperation);
    const controller = new AbortController();
    const abortedRequest = replayableRequest("aborted", controller.signal);
    const activeRequest = replayableRequest("active");

    const abortedResult = executor.execute(abortedRequest);
    const activeResult = executor.execute(activeRequest);
    await vi.waitFor(() => {
      expect(refreshOperation).toHaveBeenCalledTimes(1);
    });
    controller.abort("Caller canceled");
    refreshAttempt.resolve({
      status: "refreshed",
      session: session("replacement-access-token"),
    });

    await expect(abortedResult).resolves.toMatchObject({
      kind: "transport-error",
      error: { kind: "aborted" },
    });
    await expect(activeResult).resolves.toMatchObject({
      kind: "response",
      response: { status: 200 },
    });
    expect(refreshOperation).toHaveBeenCalledTimes(1);
    expect(abortedRequest.createRequest).toHaveBeenCalledTimes(1);
    expect(activeRequest.createRequest).toHaveBeenCalledTimes(2);
  });
});
