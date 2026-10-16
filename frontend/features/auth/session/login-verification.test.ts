import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  HttpRequest,
  HttpTransportResult,
} from "../../../lib/api/http-transport";
import { createAuthSessionService } from "./auth-session-service";

function response(status: number, value: unknown = null): HttpTransportResult {
  return {
    kind: "response",
    response: {
      status,
      ok: status >= 200 && status < 300,
      headers: new Headers(),
      body: value === null ? { kind: "empty" } : { kind: "json", value },
    },
  };
}

const credentials = (token = "candidate-access") =>
  response(200, {
    accessToken: token,
    expiresIn: 300,
    tokenType: "Bearer",
  });
const user = (teacher = false) =>
  response(200, {
    id: teacher
      ? "8ad4c564-3c27-4e6d-91aa-a004334aa8f8"
      : "8ad4c564-3c27-4e6d-91aa-a004334aa8f9",
    email: teacher ? "teacher@gmail.com" : "student@gmail.com",
    username: teacher ? "teacher" : "student",
    roles: teacher ? ["STUDENT", "TEACHER"] : ["STUDENT"],
  });
const invalidRefresh = () =>
  response(401, {
    code: "AUTH_REFRESH_FAILED",
    message: "Refresh failed.",
    path: "/api/auth/refresh",
    status: 401,
    traceId: null,
  });
const unavailable = () => response(503);
const input = { identifier: "student", password: "secret" };

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

function harness() {
  const me = vi.fn<(request: HttpRequest) => Promise<HttpTransportResult>>(
    async () => unavailable(),
  );
  const refresh = vi.fn(async () => invalidRefresh());
  const login = vi.fn(async () => credentials());
  const paths: string[] = [];
  const service = createAuthSessionService({
    transport: {
      async execute(request) {
        const path = new URL(String(request.target), "https://frontend.example")
          .pathname;
        paths.push(path);
        switch (path) {
          case "/api/auth/login":
            return login();
          case "/api/auth/refresh":
            return refresh();
          case "/api/auth/me":
            return me(request);
          case "/api/auth/logout":
            return response(204);
          case "/api/protected":
            return unavailable();
          default:
            throw new Error(`Unexpected request ${path}`);
        }
      },
    },
  });
  return { service, me, refresh, login, paths };
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("login verification outage safety", () => {
  it.each([
    ["503", unavailable()],
    ["network", { kind: "transport-error", error: { kind: "network" } }],
    ["timeout", { kind: "transport-error", error: { kind: "aborted" } }],
  ] as const)(
    "preserves refresh credentials after /me %s without granting access",
    async (_kind, failure) => {
      const { service, me, refresh, paths } = harness();
      me.mockResolvedValue(failure);

      expect((await service.login(input)).ok).toBe(false);
      expect(service.runtime.getSnapshot()).toEqual({
        status: "verification-unavailable",
      });
      expect(refresh).toHaveBeenCalledTimes(1); // Initial bootstrap only.
      expect(paths).not.toContain("/api/auth/logout");
      expect(
        await service.authenticatedRequests.execute({
          createRequest: () => ({ target: "/api/protected" }),
        }),
      ).toEqual({ kind: "authentication-failure", reason: "no-access-token" });
      expect(paths).not.toContain("/api/protected");
    },
  );

  it("recovers with the candidate token and shares simultaneous manual retries", async () => {
    const { service, me, refresh, paths } = harness();
    await service.login(input);
    const pending = deferred<HttpTransportResult>();
    me.mockImplementation(async (request) => {
      expect(new Headers(request.headers).get("authorization")).toBe(
        "Bearer candidate-access",
      );
      return pending.promise;
    });
    const first = service.retryVerification();
    const second = service.retryVerification();
    expect(second).toBe(first);
    await vi.waitFor(() => expect(me).toHaveBeenCalledTimes(2));
    expect(service.runtime.getSnapshot().status).toBe(
      "verification-unavailable",
    );
    pending.resolve(user());
    expect((await first).ok).toBe(true);
    expect(service.runtime.getSnapshot()).toMatchObject({
      status: "authenticated",
      user: { username: "student" },
    });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(paths).not.toContain("/api/auth/logout");
  });

  it("does not run automatic retries or refresh/logout loops across repeated failures", async () => {
    const { service, me, refresh, paths } = harness();
    await service.login(input);
    for (let retry = 0; retry < 3; retry += 1) {
      expect((await service.retryVerification()).ok).toBe(false);
      expect(me).toHaveBeenCalledTimes(retry + 2);
      expect(service.runtime.getSnapshot().status).toBe(
        "verification-unavailable",
      );
    }
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(paths).not.toContain("/api/auth/logout");
  });

  it("discards the previous account's token, roles and workspace during a failed switch", async () => {
    const { service, me, login } = harness();
    me.mockResolvedValueOnce(user(true));
    await service.login(input);
    service.runtime.switchWorkspace("TEACHING");
    login.mockResolvedValueOnce(credentials("new-student-access"));
    await service.login({ ...input, identifier: "new-student" });
    expect(service.runtime.getSnapshot()).toEqual({
      status: "verification-unavailable",
    });
    expect(service.runtime.switchWorkspace("TEACHING")).toBe(false);
    expect(
      await service.authenticatedRequests.executeOnce({
        createRequest: () => ({ target: "/api/protected" }),
      }),
    ).toMatchObject({ kind: "authentication-failure" });
    me.mockImplementationOnce(async (request) => {
      expect(new Headers(request.headers).get("authorization")).toBe(
        "Bearer new-student-access",
      );
      return user();
    });
    await service.retryVerification();
    expect(service.runtime.getSnapshot()).toMatchObject({
      status: "authenticated",
      activeWorkspace: "LEARNING",
      user: { username: "student", roles: ["STUDENT"] },
    });
  });

  it.each([401, 403])(
    "retains real /me %s denial behavior on login and recovery",
    async (status) => {
      const { service, me, paths } = harness();
      me.mockResolvedValueOnce(response(status));
      await service.login(input);
      expect(service.runtime.getSnapshot().status).toBe("anonymous");
      expect(paths.filter((path) => path === "/api/auth/logout")).toHaveLength(
        1,
      );

      await service.login(input);
      me.mockResolvedValueOnce(response(status));
      expect((await service.retryVerification()).ok).toBe(false);
      expect(service.runtime.getSnapshot().status).toBe("anonymous");
      expect(paths.filter((path) => path === "/api/auth/logout")).toHaveLength(
        2,
      );
      const calls = me.mock.calls.length;
      await service.retryVerification();
      expect(me).toHaveBeenCalledTimes(calls);
    },
  );

  it("refreshes an expired candidate once per explicit retry and hydrates the new token", async () => {
    const now = vi.spyOn(Date, "now").mockReturnValue(0);
    const { service, me, refresh } = harness();
    await service.login(input);
    now.mockReturnValue(300_000);
    refresh.mockResolvedValueOnce(credentials("renewed-access"));
    me.mockImplementationOnce(async (request) => {
      expect(new Headers(request.headers).get("authorization")).toBe(
        "Bearer renewed-access",
      );
      return user();
    });
    expect((await service.retryVerification()).ok).toBe(true);
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it("preserves pending verification on refresh 503 and clears it on a real invalid refresh", async () => {
    const now = vi.spyOn(Date, "now").mockReturnValue(0);
    const { service, me, refresh, paths } = harness();
    await service.login(input);
    now.mockReturnValue(300_000);
    refresh.mockResolvedValueOnce(unavailable());
    await service.retryVerification();
    expect(service.runtime.getSnapshot().status).toBe(
      "verification-unavailable",
    );
    expect(me).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledTimes(2);
    await service.retryVerification(); // AUTH_REFRESH_FAILED, no further replay.
    expect(service.runtime.getSnapshot().status).toBe("anonymous");
    await service.retryVerification();
    expect(refresh).toHaveBeenCalledTimes(3);
    expect(paths).not.toContain("/api/auth/logout");
  });

  it("does not resurrect a locally cleared session after a late verification response", async () => {
    const { service, me } = harness();
    await service.login(input);
    const pending = deferred<HttpTransportResult>();
    me.mockReturnValueOnce(pending.promise);
    const retry = service.retryVerification();
    await vi.waitFor(() => expect(me).toHaveBeenCalledTimes(2));
    service.runtime.clearLocalSession();
    pending.resolve(user());
    expect((await retry).ok).toBe(false);
    expect(service.runtime.getSnapshot().status).toBe("anonymous");
  });

  it("serializes recovery and another account login so late hydration cannot restore the old account", async () => {
    const { service, me, login } = harness();
    await service.login(input);
    const pending = deferred<HttpTransportResult>();
    me.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(user(true));
    const retry = service.retryVerification();
    await vi.waitFor(() => expect(me).toHaveBeenCalledTimes(2));
    const switchAccount = service.login({ ...input, identifier: "teacher" });
    expect(login).toHaveBeenCalledTimes(1);
    pending.resolve(user());
    await retry;
    await switchAccount;
    expect(service.runtime.getSnapshot()).toMatchObject({
      status: "authenticated",
      user: { username: "teacher" },
    });
  });

  it("queues logout after in-flight verification and leaves no authenticated session", async () => {
    const { service, me, paths } = harness();
    await service.login(input);
    const pending = deferred<HttpTransportResult>();
    me.mockReturnValueOnce(pending.promise);
    const retry = service.retryVerification();
    await vi.waitFor(() => expect(me).toHaveBeenCalledTimes(2));
    const logout = service.logout();
    expect(paths).not.toContain("/api/auth/logout");
    pending.resolve(user());
    await retry;
    await logout;
    expect(service.runtime.getSnapshot().status).toBe("anonymous");
    expect((await service.retryVerification()).ok).toBe(false);
  });

  it("bounds stalled /me verification to ten seconds and preserves recovery", async () => {
    vi.useFakeTimers();
    const { service, me, paths } = harness();
    me.mockImplementation(
      (request) =>
        new Promise((resolve) => {
          request.signal?.addEventListener(
            "abort",
            () =>
              resolve({ kind: "transport-error", error: { kind: "aborted" } }),
            { once: true },
          );
        }),
    );
    const attempt = service.login(input);
    await vi.advanceTimersByTimeAsync(10_000);
    expect((await attempt).ok).toBe(false);
    expect(service.runtime.getSnapshot().status).toBe(
      "verification-unavailable",
    );
    expect(paths).not.toContain("/api/auth/logout");
  });

  it("passes protected 503 through without refresh, logout or local session expiration", async () => {
    const { service, me, refresh, paths } = harness();
    me.mockResolvedValueOnce(user());
    await service.login(input);
    const result = await service.authenticatedRequests.execute({
      createRequest: () => ({ target: "/api/protected" }),
    });
    expect(result).toMatchObject({
      kind: "response",
      response: { status: 503 },
    });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(paths).not.toContain("/api/auth/logout");
    expect(service.runtime.getSnapshot().status).toBe("authenticated");
  });
});
