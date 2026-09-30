import { describe, expect, it, vi } from "vitest";
import type {
  HttpRequest,
  HttpTransport,
  HttpTransportResult,
} from "../../../lib/api/http-transport";
import { createAuthSessionService } from "./auth-session-service";

const userId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";

function jsonResponse(status: number, value: unknown): HttpTransportResult {
  return {
    kind: "response",
    response: {
      body: { kind: "json", value },
      headers: new Headers({ "content-type": "application/json" }),
      ok: status >= 200 && status < 300,
      status,
    },
  };
}

function emptyResponse(status: number): HttpTransportResult {
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

function loginResponse(accessToken: string) {
  return jsonResponse(200, {
    accessToken,
    expiresIn: 300,
    tokenType: "Bearer",
  });
}

function currentUser(
  roles: readonly string[] = ["STUDENT"],
): HttpTransportResult {
  return jsonResponse(200, {
    email: "learner01@gmail.com",
    id: userId,
    roles,
    username: "learner01",
  });
}

function refreshFailure(): HttpTransportResult {
  return jsonResponse(401, {
    code: "AUTH_REFRESH_FAILED",
    message: "Refresh failed.",
    path: "/api/auth/refresh",
    status: 401,
    traceId: null,
  });
}

function targetPath(request: HttpRequest): string {
  return new URL(String(request.target), "https://frontend.example").pathname;
}

function authorization(request: HttpRequest): string | null {
  return new Headers(request.headers).get("authorization");
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

describe("auth session service", () => {
  it("restores a page-load session with exactly one cookie refresh and authoritative /me hydration", async () => {
    const requests: HttpRequest[] = [];
    const transport: HttpTransport = {
      async execute(request) {
        requests.push(request);
        if (targetPath(request) === "/api/auth/refresh") {
          return loginResponse("bootstrap-access");
        }
        if (targetPath(request) === "/api/auth/me") {
          expect(authorization(request)).toBe("Bearer bootstrap-access");
          return currentUser(["STUDENT", "TEACHER"]);
        }
        throw new Error(`Unexpected request ${String(request.target)}`);
      },
    };
    const service = createAuthSessionService({ transport });

    const result = await service.bootstrap();

    expect(result.status).toBe("refreshed");
    expect(
      requests.filter((request) => targetPath(request) === "/api/auth/refresh"),
    ).toHaveLength(1);
    expect(requests[0]?.credentials).toBe("include");
    expect(service.runtime.getSnapshot()).toEqual({
      activeWorkspace: "LEARNING",
      status: "authenticated",
      user: {
        email: "learner01@gmail.com",
        id: userId,
        roles: ["STUDENT", "TEACHER"],
        username: "learner01",
      },
    });
  });

  it("settles bootstrap cleanly as anonymous when the refresh family is absent", async () => {
    const execute = vi.fn(async () => refreshFailure());
    const service = createAuthSessionService({ transport: { execute } });

    await expect(service.bootstrap()).resolves.toEqual({
      status: "no-session",
    });
    expect(execute).toHaveBeenCalledTimes(1);
    expect(service.runtime.getSnapshot()).toEqual({ status: "anonymous" });
    await expect(
      service.authenticatedRequests.executeOnce({
        createRequest: () => ({ target: "/api/protected" }),
      }),
    ).resolves.toEqual({
      kind: "authentication-failure",
      reason: "no-access-token",
    });
  });

  it("logs in with identifier credentials, hydrates /me, and never derives profile data from the form", async () => {
    const requests: HttpRequest[] = [];
    const transport: HttpTransport = {
      async execute(request) {
        requests.push(request);
        switch (targetPath(request)) {
          case "/api/auth/refresh":
            return refreshFailure();
          case "/api/auth/login":
            return loginResponse("login-access");
          case "/api/auth/me":
            return currentUser(["STUDENT"]);
          default:
            throw new Error(`Unexpected request ${String(request.target)}`);
        }
      },
    };
    const service = createAuthSessionService({ transport });

    const result = await service.login({
      identifier: "different-form-value@gmail.com",
      password: "secret",
    });

    expect(result).toEqual({
      ok: true,
      value: {
        email: "learner01@gmail.com",
        id: userId,
        roles: ["STUDENT"],
        username: "learner01",
      },
    });
    expect(service.runtime.getSnapshot()).toMatchObject({
      status: "authenticated",
      user: {
        email: "learner01@gmail.com",
        username: "learner01",
      },
    });
    expect(authorization(requests.at(-1)!)).toBe("Bearer login-access");
  });

  it("preserves generic invalid-credential errors and remains anonymous", async () => {
    const transport: HttpTransport = {
      async execute(request) {
        if (targetPath(request) === "/api/auth/refresh") {
          return refreshFailure();
        }
        return jsonResponse(401, {
          code: "AUTH_INVALID_CREDENTIALS",
          message: "Invalid credentials.",
          path: "/api/auth/login",
          status: 401,
          traceId: null,
        });
      },
    };
    const service = createAuthSessionService({ transport });

    const result = await service.login({
      identifier: "learner01",
      password: "wrong",
    });

    expect(result).toMatchObject({
      ok: false,
      error: {
        kind: "api-error",
        error: { code: "AUTH_INVALID_CREDENTIALS", status: 401 },
      },
    });
    expect(service.runtime.getSnapshot()).toEqual({ status: "anonymous" });
  });

  it("shares one real refresh adapter across concurrent protected 401s and retries each caller once", async () => {
    const laterRefresh = deferred<HttpTransportResult>();
    let refreshCalls = 0;
    let protectedCalls = 0;
    const transport: HttpTransport = {
      async execute(request) {
        const path = targetPath(request);
        if (path === "/api/auth/refresh") {
          refreshCalls += 1;
          if (refreshCalls === 1) {
            return loginResponse("initial-access");
          }
          return laterRefresh.promise;
        }
        if (path === "/api/auth/me") {
          return currentUser(["STUDENT"]);
        }
        if (path === "/api/protected") {
          protectedCalls += 1;
          return authorization(request) === "Bearer initial-access"
            ? jsonResponse(401, {
                code: "UNAUTHORIZED",
                message: "Unauthorized",
                path,
                status: 401,
                traceId: null,
              })
            : jsonResponse(200, { ok: true });
        }
        throw new Error(`Unexpected request ${String(request.target)}`);
      },
    };
    const service = createAuthSessionService({ transport });
    await service.bootstrap();

    const calls = Array.from({ length: 3 }, () =>
      service.authenticatedRequests.execute({
        createRequest: () => ({
          headers: { accept: "application/json" },
          method: "GET",
          target: "/api/protected",
        }),
      }),
    );

    await vi.waitFor(() => expect(refreshCalls).toBe(2));
    laterRefresh.resolve(loginResponse("replacement-access"));

    const results = await Promise.all(calls);

    expect(refreshCalls).toBe(2);
    expect(protectedCalls).toBe(6);
    expect(results.every((result) => result.kind === "response")).toBe(true);
    expect(service.runtime.getSnapshot().status).toBe("authenticated");
  });

  it("clears the in-memory session after cookie logout completes", async () => {
    let logoutObservedAuthenticated = false;
    let service!: ReturnType<typeof createAuthSessionService>;
    const transport: HttpTransport = {
      async execute(request) {
        switch (targetPath(request)) {
          case "/api/auth/refresh":
            return loginResponse("initial-access");
          case "/api/auth/me":
            return currentUser();
          case "/api/auth/logout":
            logoutObservedAuthenticated =
              service.runtime.getSnapshot().status === "authenticated";
            expect(request.credentials).toBe("include");
            return emptyResponse(204);
          default:
            throw new Error(`Unexpected request ${String(request.target)}`);
        }
      },
    };
    service = createAuthSessionService({ transport });
    await service.bootstrap();

    await expect(service.logout()).resolves.toEqual({
      ok: true,
      value: undefined,
    });

    expect(logoutObservedAuthenticated).toBe(true);
    expect(service.runtime.getSnapshot()).toEqual({ status: "anonymous" });
    await expect(
      service.authenticatedRequests.executeOnce({
        createRequest: () => ({ target: "/api/protected" }),
      }),
    ).resolves.toMatchObject({
      kind: "authentication-failure",
      reason: "no-access-token",
    });
  });

  it("waits for an in-flight refresh before logout and leaves no late token behind", async () => {
    const pendingRefresh = deferred<HttpTransportResult>();
    let refreshCalls = 0;
    let logoutCalls = 0;
    const transport: HttpTransport = {
      async execute(request) {
        switch (targetPath(request)) {
          case "/api/auth/refresh":
            refreshCalls += 1;
            return refreshCalls === 1
              ? loginResponse("initial-access")
              : pendingRefresh.promise;
          case "/api/auth/me":
            return currentUser();
          case "/api/protected":
            return authorization(request) === "Bearer initial-access"
              ? jsonResponse(401, {
                  code: "UNAUTHORIZED",
                  message: "Unauthorized",
                  path: "/api/protected",
                  status: 401,
                  traceId: null,
                })
              : jsonResponse(200, { ok: true });
          case "/api/auth/logout":
            logoutCalls += 1;
            return emptyResponse(204);
          default:
            throw new Error(`Unexpected request ${String(request.target)}`);
        }
      },
    };
    const service = createAuthSessionService({ transport });
    await service.bootstrap();

    const protectedRequest = service.authenticatedRequests.execute({
      createRequest: () => ({ target: "/api/protected" }),
    });
    await vi.waitFor(() => expect(refreshCalls).toBe(2));
    const logout = service.logout();
    expect(logoutCalls).toBe(0);

    pendingRefresh.resolve(loginResponse("replacement-access"));
    await expect(protectedRequest).resolves.toMatchObject({
      kind: "response",
      response: { status: 200 },
    });
    await expect(logout).resolves.toEqual({ ok: true, value: undefined });

    expect(logoutCalls).toBe(1);
    expect(service.runtime.getSnapshot()).toEqual({ status: "anonymous" });
    await expect(
      service.authenticatedRequests.executeOnce({
        createRequest: () => ({ target: "/api/protected" }),
      }),
    ).resolves.toEqual({
      kind: "authentication-failure",
      reason: "no-access-token",
    });
  });

  it("keeps the current session and token when /me fails transiently after rotation", async () => {
    let refreshCalls = 0;
    let meCalls = 0;
    let checkAuthorization: string | null = null;
    const transport: HttpTransport = {
      async execute(request) {
        switch (targetPath(request)) {
          case "/api/auth/refresh":
            refreshCalls += 1;
            return loginResponse(
              refreshCalls === 1 ? "initial-access" : "replacement-access",
            );
          case "/api/auth/me":
            meCalls += 1;
            return meCalls === 1
              ? currentUser()
              : {
                  kind: "transport-error",
                  error: { kind: "network", cause: new Error("offline") },
                };
          case "/api/protected":
            return jsonResponse(401, {
              code: "UNAUTHORIZED",
              message: "Unauthorized",
              path: "/api/protected",
              status: 401,
              traceId: null,
            });
          case "/api/check":
            checkAuthorization = authorization(request);
            return jsonResponse(200, { ok: true });
          default:
            throw new Error(`Unexpected request ${String(request.target)}`);
        }
      },
    };
    const service = createAuthSessionService({ transport });
    await service.bootstrap();

    await expect(
      service.authenticatedRequests.execute({
        createRequest: () => ({ target: "/api/protected" }),
      }),
    ).resolves.toMatchObject({ kind: "refresh-failure" });

    expect(service.runtime.getSnapshot().status).toBe("authenticated");
    await service.authenticatedRequests.executeOnce({
      createRequest: () => ({ target: "/api/check" }),
    });
    expect(checkAuthorization).toBe("Bearer initial-access");
  });

  it("retains the authenticated local session when server logout fails", async () => {
    let protectedAuthorization: string | null = null;
    const transport: HttpTransport = {
      async execute(request) {
        switch (targetPath(request)) {
          case "/api/auth/refresh":
            return loginResponse("initial-access");
          case "/api/auth/me":
            return currentUser();
          case "/api/auth/logout":
            return {
              kind: "transport-error",
              error: { kind: "network", cause: new Error("offline") },
            };
          case "/api/protected":
            protectedAuthorization = authorization(request);
            return jsonResponse(200, { ok: true });
          default:
            throw new Error(`Unexpected request ${String(request.target)}`);
        }
      },
    };
    const service = createAuthSessionService({ transport });
    await service.bootstrap();

    await expect(service.logout()).resolves.toMatchObject({
      ok: false,
      error: { kind: "transport-error" },
    });
    expect(service.runtime.getSnapshot().status).toBe("authenticated");
    await service.authenticatedRequests.executeOnce({
      createRequest: () => ({ target: "/api/protected" }),
    });
    expect(protectedAuthorization).toBe("Bearer initial-access");
  });

  it("does not persist access or refresh credentials through browser storage", async () => {
    const localStorageSet = vi.spyOn(Storage.prototype, "setItem");
    const localStorageRemove = vi.spyOn(Storage.prototype, "removeItem");
    const transport: HttpTransport = {
      async execute(request) {
        switch (targetPath(request)) {
          case "/api/auth/refresh":
            return refreshFailure();
          case "/api/auth/login":
            return loginResponse("memory-only-access");
          case "/api/auth/me":
            return currentUser();
          default:
            throw new Error(`Unexpected request ${String(request.target)}`);
        }
      },
    };
    const service = createAuthSessionService({ transport });

    await service.login({
      identifier: "learner01",
      password: "secret",
    });

    expect(localStorageSet).not.toHaveBeenCalled();
    expect(localStorageRemove).not.toHaveBeenCalled();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);

    localStorageSet.mockRestore();
    localStorageRemove.mockRestore();
  });
});
