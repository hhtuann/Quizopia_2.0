import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createFetchHttpTransport,
  type HttpRequest,
  type HttpTransportResult,
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
const credentials = (token = "candidate") =>
  response(200, { accessToken: token, expiresIn: 300, tokenType: "Bearer" });
const profile = (teacher = false) =>
  response(200, {
    id: teacher
      ? "8ad4c564-3c27-4e6d-91aa-a004334aa8f8"
      : "8ad4c564-3c27-4e6d-91aa-a004334aa8f9",
    username: teacher ? "teacher" : "student",
    email: teacher ? "teacher@gmail.com" : "student@gmail.com",
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
const input = { identifier: "student", password: "test-password" };

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
type Handler = (request: HttpRequest) => Promise<HttpTransportResult>;
function harness() {
  const login = vi.fn<Handler>(async () => credentials());
  const refresh = vi.fn<Handler>(async () => invalidRefresh());
  const me = vi.fn<Handler>(async () => profile());
  const logout = vi.fn<Handler>(async () => response(204));
  const protectedRequest = vi.fn<Handler>(async () => response(200, {}));
  const enableTeacher = vi.fn<Handler>(async () => response(204));
  const service = createAuthSessionService({
    transport: {
      async execute(request) {
        const path = String(request.target);
        if (path.endsWith("/login")) return login(request);
        if (path.endsWith("/refresh")) return refresh(request);
        if (path.endsWith("/me")) return me(request);
        if (path.endsWith("/logout")) return logout(request);
        if (path === "/api/protected") return protectedRequest(request);
        if (path.endsWith("/teacher-enablement")) return enableTeacher(request);
        throw new Error(`Unexpected request ${path}`);
      },
    },
  });
  return {
    service,
    login,
    refresh,
    me,
    logout,
    protectedRequest,
    enableTeacher,
  };
}
function holdNext(handler: ReturnType<typeof vi.fn<Handler>>) {
  const entered = deferred<HttpRequest>();
  const late = deferred<HttpTransportResult>();
  // Intentionally keep the promise pending after abort to exercise late-result
  // fencing independently of transport cooperation.
  handler.mockImplementationOnce((request) => {
    entered.resolve(request);
    return late.promise;
  });
  return { entered, late };
}
const phases = [
  "login-credentials",
  "login-hydration",
  "bootstrap-refresh",
  "bootstrap-hydration",
] as const;
async function pendingPhase(phase: (typeof phases)[number]) {
  const h = harness();
  if (phase === "bootstrap-hydration")
    h.refresh.mockResolvedValueOnce(credentials());
  const gate = holdNext(
    phase === "login-credentials"
      ? h.login
      : phase === "bootstrap-refresh"
        ? h.refresh
        : h.me,
  );
  const attempt = phase.startsWith("login")
    ? h.service.login(input)
    : h.service.bootstrap();
  const request = await gate.entered.promise;
  return { ...h, ...gate, attempt, request };
}
async function signInTeacher(h: ReturnType<typeof harness>) {
  h.login.mockResolvedValueOnce(credentials("teacher-access"));
  h.me.mockResolvedValueOnce(profile(true));
  expect((await h.service.login({ ...input, identifier: "teacher" })).ok).toBe(
    true,
  );
}
async function expectTeacher(h: ReturnType<typeof harness>) {
  expect(h.service.runtime.getSnapshot()).toMatchObject({
    status: "authenticated",
    user: { username: "teacher", roles: ["STUDENT", "TEACHER"] },
  });
  await h.service.authenticatedRequests.executeOnce({
    createRequest: () => ({ target: "/api/protected" }),
  });
  expect(
    new Headers(h.protectedRequest.mock.lastCall?.[0].headers).get(
      "authorization",
    ),
  ).toBe("Bearer teacher-access");
}
const originalLocks = Object.getOwnPropertyDescriptor(navigator, "locks");
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
  if (originalLocks === undefined)
    delete (navigator as { locks?: LockManager }).locks;
  else Object.defineProperty(navigator, "locks", originalLocks);
});

describe("complete login/bootstrap generation fencing", () => {
  describe.each(phases)("%s", (phase) => {
    it.each(["success", "invalid", "unavailable"] as const)(
      "ignores late %s after local clear and a newer account",
      async (outcome) => {
        const h = await pendingPhase(phase);
        h.service.runtime.clearLocalSession();
        await h.attempt;
        expect(h.request.signal?.aborted).toBe(true);
        expect(h.service.runtime.getSnapshot()).toEqual({
          status: "anonymous",
        });
        expect(h.logout).not.toHaveBeenCalled();
        await signInTeacher(h);
        h.late.resolve(
          outcome === "success"
            ? phase.endsWith("hydration")
              ? profile()
              : credentials("obsolete")
            : response(outcome === "invalid" ? 401 : 503),
        );
        await h.late.promise;
        await expectTeacher(h);
        expect(h.logout).not.toHaveBeenCalled();
      },
    );
  });

  it.each(["success", "invalid", "unavailable"] as const)(
    "supersedes an older login before its late %s without requiring local clear",
    async (outcome) => {
      const h = await pendingPhase("login-credentials");
      await signInTeacher(h);
      expect(await h.attempt).toMatchObject({ ok: false });
      h.late.resolve(
        outcome === "success"
          ? credentials("obsolete")
          : response(outcome === "invalid" ? 401 : 503),
      );
      await h.late.promise;
      await expectTeacher(h);
      expect(h.logout).not.toHaveBeenCalled();
    },
  );

  it.each(["login-credentials", "login-hydration"] as const)(
    "allows logout before a late %s completion and prevents resurrection",
    async (phase) => {
      const h = await pendingPhase(phase);
      expect((await h.service.logout()).ok).toBe(true);
      h.late.resolve(
        phase === "login-credentials" ? credentials("obsolete") : profile(),
      );
      await h.attempt;
      await h.late.promise;
      expect(h.service.runtime.getSnapshot()).toEqual({ status: "anonymous" });
      expect(h.logout).toHaveBeenCalledTimes(1);
    },
  );

  it("cancels a login while it waits for its initial bootstrap", async () => {
    const h = harness();
    const gate = holdNext(h.refresh);
    const login = h.service.login(input);
    await gate.entered.promise;
    h.service.runtime.clearLocalSession();
    expect(await login).toMatchObject({ ok: false });
    gate.late.resolve(credentials("obsolete"));
    expect(h.login).not.toHaveBeenCalled();
    expect(h.me).not.toHaveBeenCalled();
    expect(h.service.runtime.getSnapshot()).toEqual({ status: "anonymous" });
  });

  it("allows a new login to supersede an already-running bootstrap", async () => {
    const h = await pendingPhase("bootstrap-refresh");
    await signInTeacher(h);
    await h.attempt;
    h.late.resolve(invalidRefresh());
    await h.late.promise;
    await expectTeacher(h);
    expect(h.logout).not.toHaveBeenCalled();
  });

  it("does not run an older queued logout against a newer login intent", async () => {
    const h = harness();
    await h.service.login(input);
    const logout = h.service.logout();
    await signInTeacher(h);
    expect(await logout).toMatchObject({ ok: false });
    expect(h.logout).not.toHaveBeenCalled();
    await expectTeacher(h);
  });

  it("does not rotate or expire the new account for a protected 401 received during login", async () => {
    const h = harness();
    await h.service.login(input);
    const gate = holdNext(h.login);
    h.me.mockResolvedValueOnce(profile(true));
    const login = h.service.login({ ...input, identifier: "teacher" });
    await gate.entered.promise;
    const refreshing = deferred<void>();
    const unsubscribe = h.service.runtime.subscribe(() => {
      if (h.service.runtime.getSnapshot().status === "refreshing")
        refreshing.resolve();
    });
    h.protectedRequest.mockResolvedValueOnce(response(401));
    const oldRequest = h.service.authenticatedRequests.execute({
      createRequest: () => ({ target: "/api/protected" }),
    });
    await refreshing.promise;
    gate.late.resolve(credentials("teacher-access"));
    expect((await login).ok).toBe(true);
    await oldRequest;
    unsubscribe();
    await expectTeacher(h);
    expect(h.refresh).toHaveBeenCalledTimes(1); // Bootstrap only; no refresh of the new cookie.
    expect(h.logout).not.toHaveBeenCalled();
  });
});

describe("expired-candidate recovery cancellation", () => {
  async function expiredCandidate() {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    const h = harness();
    h.me.mockResolvedValueOnce(response(503));
    await h.service.login(input);
    vi.setSystemTime(300_000);
    return h;
  }

  it("aborts stalled refresh, releases the browser lock and queue, and allows logout", async () => {
    let heldLocks = 0;
    Object.defineProperty(navigator, "locks", {
      configurable: true,
      value: {
        request: async <T>(
          _name: string,
          _options: LockOptions,
          work: () => Promise<T>,
        ): Promise<T> => {
          heldLocks += 1;
          try {
            return await work();
          } finally {
            heldLocks -= 1;
          }
        },
      },
    });
    const h = await expiredCandidate();
    const gate = holdNext(h.refresh);
    const retry = h.service.retryVerification();
    expect(h.service.retryVerification()).toBe(retry);
    const request = await gate.entered.promise;
    expect(request.signal).toBeInstanceOf(AbortSignal);
    expect(heldLocks).toBe(1);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(request.signal?.aborted).toBe(true);
    expect(await retry).toMatchObject({
      ok: false,
      error: { kind: "transport-error", error: { kind: "aborted" } },
    });
    expect(heldLocks).toBe(0);
    expect(h.refresh).toHaveBeenCalledTimes(2);
    expect(h.me).toHaveBeenCalledTimes(1);
    expect(h.logout).not.toHaveBeenCalled();
    expect(h.service.runtime.getSnapshot().status).toBe(
      "verification-unavailable",
    );
    expect((await h.service.logout()).ok).toBe(true);
    expect(heldLocks).toBe(0);
    gate.late.resolve(credentials("obsolete"));
    await vi.advanceTimersByTimeAsync(0);
    expect(h.service.runtime.getSnapshot()).toEqual({ status: "anonymous" });
    expect(h.logout).toHaveBeenCalledTimes(1);
  });

  it.each(["success", "invalid"] as const)(
    "allows later explicit recovery and ignores the timed-out request's late %s",
    async (outcome) => {
      const storage = vi.spyOn(Storage.prototype, "setItem");
      const h = await expiredCandidate();
      const gate = holdNext(h.refresh);
      const first = h.service.retryVerification();
      await gate.entered.promise;
      await vi.advanceTimersByTimeAsync(10_000);
      await first;
      h.refresh.mockResolvedValueOnce(credentials("renewed"));
      expect((await h.service.retryVerification()).ok).toBe(true);
      gate.late.resolve(
        outcome === "success" ? credentials("obsolete") : invalidRefresh(),
      );
      await vi.advanceTimersByTimeAsync(0);
      expect(h.service.runtime.getSnapshot()).toMatchObject({
        status: "authenticated",
        user: { username: "student" },
      });
      await h.service.authenticatedRequests.executeOnce({
        createRequest: () => ({ target: "/api/protected" }),
      });
      expect(
        new Headers(h.protectedRequest.mock.lastCall?.[0].headers).get(
          "authorization",
        ),
      ).toBe("Bearer renewed");
      expect(h.refresh).toHaveBeenCalledTimes(3);
      expect(h.logout).not.toHaveBeenCalled();
      expect(storage).not.toHaveBeenCalled();
    },
  );

  it("cancels expired-candidate recovery on account switch and ignores its old response", async () => {
    const h = await expiredCandidate();
    const gate = holdNext(h.refresh);
    const retry = h.service.retryVerification();
    const request = await gate.entered.promise;
    await signInTeacher(h);
    expect(request.signal?.aborted).toBe(true);
    expect((await retry).ok).toBe(false);
    gate.late.resolve(credentials("obsolete"));
    await vi.advanceTimersByTimeAsync(0);
    await expectTeacher(h);
    expect(h.logout).not.toHaveBeenCalled();
  });

  it("retains recoverability if explicit logout fails after canceling recovery", async () => {
    const h = await expiredCandidate();
    const gate = holdNext(h.refresh);
    const retry = h.service.retryVerification();
    await gate.entered.promise;
    h.logout.mockResolvedValueOnce(response(503));
    expect((await h.service.logout()).ok).toBe(false);
    await retry;
    h.refresh.mockResolvedValueOnce(credentials("renewed"));
    expect((await h.service.retryVerification()).ok).toBe(true);
    gate.late.resolve(invalidRefresh());
    await vi.advanceTimersByTimeAsync(0);
    expect(h.service.runtime.getSnapshot().status).toBe("authenticated");
    expect(h.logout).toHaveBeenCalledTimes(1);
  });

  it.each([503, "network"] as const)(
    "preserves credentials after refresh %s without automatic retry",
    async (failure) => {
      const h = await expiredCandidate();
      h.refresh.mockResolvedValueOnce(
        failure === "network"
          ? { kind: "transport-error", error: { kind: "network" } }
          : response(failure),
      );
      expect((await h.service.retryVerification()).ok).toBe(false);
      await vi.advanceTimersByTimeAsync(30_000);
      expect(h.refresh).toHaveBeenCalledTimes(2);
      expect(h.logout).not.toHaveBeenCalled();
      expect(h.service.runtime.getSnapshot().status).toBe(
        "verification-unavailable",
      );
      expect((await h.service.retryVerification()).ok).toBe(false); // Real AUTH_REFRESH_FAILED.
      expect(h.service.runtime.getSnapshot().status).toBe("anonymous");
    },
  );

  it("propagates the refresh deadline through the fetch transport", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    let refreshes = 0;
    const entered = deferred<AbortSignal>();
    const observedAbort = vi.fn();
    const fetcher = vi.fn<typeof fetch>(async (target, init) => {
      const path = String(target);
      if (path.endsWith("/refresh")) {
        refreshes += 1;
        if (refreshes === 1)
          return Response.json(
            {
              code: "AUTH_REFRESH_FAILED",
              message: "Refresh failed.",
              path,
              status: 401,
              traceId: null,
            },
            { status: 401 },
          );
        const signal = init?.signal;
        if (!signal) throw new Error("Missing refresh AbortSignal");
        entered.resolve(signal);
        return new Promise<Response>((_resolve, reject) =>
          signal.addEventListener(
            "abort",
            () => {
              observedAbort();
              reject(new DOMException("Aborted", "AbortError"));
            },
            { once: true },
          ),
        );
      }
      if (path.endsWith("/login"))
        return Response.json({
          accessToken: "candidate",
          expiresIn: 300,
          tokenType: "Bearer",
        });
      if (path.endsWith("/me")) return new Response(null, { status: 503 });
      if (path.endsWith("/logout")) return new Response(null, { status: 204 });
      throw new Error(`Unexpected request ${path}`);
    });
    const service = createAuthSessionService({
      transport: createFetchHttpTransport(fetcher),
    });
    await service.login(input);
    vi.setSystemTime(300_000);
    const retry = service.retryVerification();
    const signal = await entered.promise;
    await vi.advanceTimersByTimeAsync(10_000);
    expect((await retry).ok).toBe(false);
    expect(signal.aborted).toBe(true);
    expect(observedAbort).toHaveBeenCalledTimes(1);
    expect((await service.logout()).ok).toBe(true);
    expect(refreshes).toBe(2);
  });
});

describe("Dev 2 takeover regressions", () => {
  it("retains bootstrap's candidate for manual authoritative recovery without another rotation", async () => {
    const h = harness();
    h.refresh.mockResolvedValueOnce(credentials("bootstrap-candidate"));
    h.me.mockResolvedValueOnce(response(503));
    expect(await h.service.bootstrap()).toMatchObject({
      status: "transient-failure",
    });
    expect(h.service.runtime.getSnapshot().status).toBe(
      "verification-unavailable",
    );
    expect(
      await h.service.authenticatedRequests.executeOnce({
        createRequest: () => ({ target: "/api/protected" }),
      }),
    ).toMatchObject({ kind: "authentication-failure" });
    expect(h.protectedRequest).not.toHaveBeenCalled();
    expect((await h.service.retryVerification()).ok).toBe(true);
    expect(
      new Headers(h.me.mock.lastCall?.[0].headers).get("authorization"),
    ).toBe("Bearer bootstrap-candidate");
    expect(h.refresh).toHaveBeenCalledTimes(1);
    expect(h.logout).not.toHaveBeenCalled();
  });

  it.each(["execute", "executeOnce"] as const)(
    "discards %s success from a previous account",
    async (method) => {
      const h = harness();
      await h.service.login(input);
      const gate = holdNext(h.protectedRequest);
      const request = h.service.authenticatedRequests[method]({
        createRequest: () => ({ target: "/api/protected" }),
      });
      await gate.entered.promise;
      await signInTeacher(h);
      gate.late.resolve(
        response(200, { privateStudentData: "must not escape" }),
      );
      expect(await request).toEqual({
        kind: "authentication-failure",
        reason: "session-changed",
      });
      await expectTeacher(h);
    },
  );

  it("discards successful protected replay after local clear", async () => {
    const h = harness();
    await h.service.login(input);
    h.protectedRequest.mockResolvedValueOnce(response(401));
    h.refresh.mockResolvedValueOnce(credentials("replacement"));
    const gate = holdNext(h.protectedRequest);
    const request = h.service.authenticatedRequests.execute({
      createRequest: () => ({ target: "/api/protected" }),
    });
    await gate.entered.promise;
    h.service.runtime.clearLocalSession();
    gate.late.resolve(response(200, { privateStudentData: "must not escape" }));
    expect(await request).toEqual({
      kind: "authentication-failure",
      reason: "session-changed",
    });
  });

  it("does not refresh a newer identity after stale Teacher enablement success", async () => {
    const h = harness();
    await h.service.login(input);
    const gate = holdNext(h.enableTeacher);
    const enabling = h.service.enableTeacher();
    await gate.entered.promise;
    await signInTeacher(h);
    gate.late.resolve(response(204));
    expect((await enabling).ok).toBe(false);
    expect(h.refresh).toHaveBeenCalledTimes(1);
    await expectTeacher(h);
  });

  it.each([false, true])(
    "bounds ordinary refresh and releases logout (Web Locks=%s)",
    async (useLocks) => {
      vi.useFakeTimers();
      let held = 0;
      if (useLocks)
        Object.defineProperty(navigator, "locks", {
          configurable: true,
          value: {
            request: async <T>(
              _name: string,
              _options: LockOptions,
              work: () => Promise<T>,
            ) => {
              held += 1;
              try {
                return await work();
              } finally {
                held -= 1;
              }
            },
          },
        });
      const h = harness();
      await h.service.login(input);
      h.protectedRequest.mockResolvedValueOnce(response(401));
      const gate = holdNext(h.refresh);
      const request = h.service.authenticatedRequests.execute({
        createRequest: () => ({ target: "/api/protected" }),
      });
      const refreshing = await gate.entered.promise;
      const logout = h.service.logout();
      await vi.advanceTimersByTimeAsync(10_000);
      expect(refreshing.signal?.aborted).toBe(true);
      expect(await request).toMatchObject({ kind: "refresh-failure" });
      expect((await logout).ok).toBe(true);
      expect(held).toBe(0);
      gate.late.resolve(credentials("obsolete"));
      await vi.advanceTimersByTimeAsync(0);
      expect(h.service.runtime.getSnapshot().status).toBe("anonymous");
      await signInTeacher(h);
    },
  );

  it("bounds bootstrap refresh with a non-settling transport", async () => {
    vi.useFakeTimers();
    const h = harness();
    const gate = holdNext(h.refresh);
    const attempt = h.service.bootstrap();
    const request = await gate.entered.promise;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(await attempt).toMatchObject({ status: "transient-failure" });
    expect(request.signal?.aborted).toBe(true);
    expect(h.service.runtime.getSnapshot().status).toBe("bootstrap-failed");
    expect(h.logout).not.toHaveBeenCalled();
    await signInTeacher(h);
    gate.late.resolve(credentials("obsolete"));
    await vi.advanceTimersByTimeAsync(0);
    await expectTeacher(h);
  });

  it("bounds queued Web Lock acquisition and ignores a non-cooperating late callback", async () => {
    vi.useFakeTimers();
    const h = harness();
    h.me.mockResolvedValueOnce(response(503));
    await h.service.login(input);
    const entered = deferred<AbortSignal>();
    let lateWork!: () => Promise<unknown>;
    Object.defineProperty(navigator, "locks", {
      configurable: true,
      value: {
        request: (
          _name: string,
          options: LockOptions,
          work: () => Promise<unknown>,
        ) => {
          lateWork = work;
          entered.resolve(options.signal!);
          return new Promise(() => {});
        },
      },
    });
    const retry = h.service.retryVerification();
    const signal = await entered.promise;
    await vi.advanceTimersByTimeAsync(10_000);
    expect((await retry).ok).toBe(false);
    expect(signal.aborted).toBe(true);
    expect(h.me).toHaveBeenCalledTimes(1);
    await lateWork();
    expect(h.me).toHaveBeenCalledTimes(1);
    delete (navigator as { locks?: LockManager }).locks;
    expect((await h.service.logout()).ok).toBe(true);
  });

  it("cancels queued Web Lock acquisition when a new login supersedes it", async () => {
    const h = harness();
    h.me.mockResolvedValueOnce(response(503));
    await h.service.login(input);
    const entered = deferred<AbortSignal>();
    Object.defineProperty(navigator, "locks", {
      configurable: true,
      value: {
        request: (_name: string, options: LockOptions) => {
          entered.resolve(options.signal!);
          return new Promise(() => {});
        },
      },
    });
    const retry = h.service.retryVerification();
    const signal = await entered.promise;
    delete (navigator as { locks?: LockManager }).locks;
    await signInTeacher(h);
    expect(signal.aborted).toBe(true);
    expect((await retry).ok).toBe(false);
  });

  it.each([
    response(200, {}),
    response(401),
    response(403),
    response(429),
    response(500),
  ])(
    "keeps hydration status classification fail-closed: %j",
    async (failure) => {
      const h = harness();
      h.me.mockResolvedValueOnce(failure);
      expect((await h.service.login(input)).ok).toBe(false);
      const status = failure.kind === "response" ? failure.response.status : 0;
      expect(h.service.runtime.getSnapshot().status).toBe(
        [401, 403].includes(status) ? "anonymous" : "verification-unavailable",
      );
      expect(h.logout).toHaveBeenCalledTimes(
        [401, 403].includes(status) ? 1 : 0,
      );
      expect(h.protectedRequest).not.toHaveBeenCalled();
    },
  );
});

it("does not recover bootstrap's account after a newer login fails", async () => {
  const h = harness();
  h.refresh.mockResolvedValueOnce(credentials("bootstrap-account"));
  h.me.mockResolvedValueOnce(response(503));
  h.login.mockResolvedValueOnce(response(401));
  expect(
    (await h.service.login({ ...input, identifier: "another-account" })).ok,
  ).toBe(false);
  expect(h.service.runtime.getSnapshot().status).toBe("anonymous");
  expect((await h.service.retryVerification()).ok).toBe(false);
  expect(h.me).toHaveBeenCalledTimes(1);
});
