import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AuthProvider,
  useAuth,
  type AuthContextValue,
} from "../../auth/auth-provider";
import { createAuthenticatedUser } from "../../auth/model/authenticated-user";
import { createAccessTokenVault } from "../../auth/session/access-token-vault";
import { createAuthSessionService } from "../../auth/session/auth-session-service";
import { createSessionRuntime } from "../../auth/session/session-runtime";
import type {
  HttpRequest,
  HttpTransportResult,
} from "../../../lib/api/http-transport";
import { usePublicationClient } from "../hooks/use-publication-client";
import {
  createPublicationApiClient,
  type PublicationApiClient,
} from "./publication-api-client";
import type {
  Configuration,
  OwnerView,
} from "./publication-contract.generated";

const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const teacherId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const otherTeacherId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f9";
const operationKey = "publication_operation_123456";
const configuration: Configuration = {
  title: "Session isolation assessment",
  quizId: "3ad4c564-3c27-4e6d-91aa-a004334aa8f8",
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
  publicationId,
  configuration,
  state: "DRAFT",
  revision: 0,
  createdAt: "2026-10-16T07:00:00Z",
  openedAt: null,
  privateLocator: "A".repeat(43),
};
const input = { identifier: "teacher", password: "test-only-password" };

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

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

function credentials(token: string) {
  return response(200, {
    accessToken: token,
    expiresIn: 300,
    tokenType: "Bearer",
  });
}

function profile(id = teacherId) {
  return response(200, {
    id,
    email: "teacher@gmail.com",
    username: id === teacherId ? "teacher" : "other-teacher",
    roles: ["STUDENT", "TEACHER"],
  });
}

function invalidRefresh() {
  return response(401, {
    code: "AUTH_REFRESH_FAILED",
    message: "Refresh failed.",
    path: "/api/auth/refresh",
    status: 401,
    traceId: null,
  });
}

function harness() {
  const requests: HttpRequest[] = [];
  const me = vi.fn<(request: HttpRequest) => Promise<HttpTransportResult>>(
    async () => profile(),
  );
  const refresh = vi.fn<(request: HttpRequest) => Promise<HttpTransportResult>>(
    async () => invalidRefresh(),
  );
  const login = vi.fn(async () => credentials("teacher-access"));
  const logout = vi.fn(async () => response(204));
  const publication = vi.fn<
    (request: HttpRequest) => Promise<HttpTransportResult>
  >(async () => response(200, owner));
  const service = createAuthSessionService({
    apiBaseUrl: "http://localhost:8080",
    transport: {
      async execute(request) {
        requests.push(request);
        const path = new URL(String(request.target), "http://localhost:8080")
          .pathname;
        if (path === "/api/auth/login") return login();
        if (path === "/api/auth/me") return me(request);
        if (path === "/api/auth/refresh") return refresh(request);
        if (path === "/api/auth/logout") return logout();
        if (path.startsWith("/api/assessments/publications"))
          return publication(request);
        throw new Error(`Unexpected test request ${path}`);
      },
    },
  });
  function client() {
    return createPublicationApiClient({
      apiBaseUrl: "http://localhost:8080",
      authenticatedRequests: service.authenticatedRequests,
      getSessionGeneration: service.runtime.getSessionGeneration,
    });
  }
  async function signIn() {
    expect((await service.login(input)).ok).toBe(true);
    expect(service.runtime.switchWorkspace("TEACHING")).toBe(true);
    return client();
  }
  return {
    service,
    me,
    refresh,
    login,
    logout,
    publication,
    requests,
    client,
    signIn,
  };
}

// The bridge uses both production context and feature-hook composition. It
// intentionally retains old client references so late callbacks are exercised.
function contextBridge(service: ReturnType<typeof createAuthSessionService>) {
  let current!: {
    readonly auth: AuthContextValue;
    readonly client: PublicationApiClient | null;
  };
  function Probe() {
    const auth = useAuth();
    const client = usePublicationClient();
    current = { auth, client };
    return <p>{auth.user?.id ?? auth.session.status}</p>;
  }
  const rendered = render(
    <AuthProvider service={service}>
      <Probe />
    </AuthProvider>,
  );
  return { read: () => current, unmount: rendered.unmount };
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("merged LD-05 session and Publication composition", () => {
  it.each([200, 401])(
    "rejects late owner HTTP %i after real logout and a different account login",
    async (status) => {
      const h = harness();
      await h.signIn();
      const bridge = contextBridge(h.service);
      const oldClient = bridge.read().client!;
      const oldGeneration = bridge.read().auth.getSessionGeneration();
      const pending = deferred<HttpTransportResult>();
      h.publication.mockReturnValueOnce(pending.promise);
      const obsolete = oldClient.get(publicationId);
      await act(async () => {
        expect((await h.service.logout()).ok).toBe(true);
        h.login.mockResolvedValueOnce(credentials("other-account-access"));
        h.me.mockResolvedValueOnce(profile(otherTeacherId));
        expect(
          (await h.service.login({ ...input, identifier: "other-teacher" })).ok,
        ).toBe(true);
      });
      expect(bridge.read().auth.getSessionGeneration()).not.toBe(oldGeneration);
      expect(bridge.read().auth.getSessionGeneration()).toBe(
        h.service.runtime.getSessionGeneration(),
      );
      pending.resolve(
        response(status, status === 200 ? owner : { code: "UNAUTHENTICATED" }),
      );
      await expect(obsolete).resolves.toEqual({
        ok: false,
        error: { kind: "session" },
      });
      await expect(
        oldClient.open(publicationId, { expectedRevision: 0 }),
      ).resolves.toEqual({ ok: false, error: { kind: "session" } });
      expect(h.publication).toHaveBeenCalledTimes(1);
      expect(h.refresh).toHaveBeenCalledTimes(1); // Only initial anonymous bootstrap.
      expect(h.logout).toHaveBeenCalledTimes(1);
      expect(screen.getByText(otherTeacherId)).toBeInTheDocument();
      expect(h.service.runtime.getSnapshot()).toMatchObject({
        status: "authenticated",
        activeWorkspace: "LEARNING",
        user: { id: otherTeacherId },
      });
      await expect(
        bridge.read().client!.get(publicationId),
      ).resolves.toMatchObject({ ok: true });
      expect(
        new Headers(h.publication.mock.calls[1]![0].headers).get(
          "authorization",
        ),
      ).toBe("Bearer other-account-access");
    },
  );

  it("invalidates an in-flight Publication payload immediately on local clear without sending cookie logout", async () => {
    const h = harness();
    const client = await h.signIn();
    const pending = deferred<HttpTransportResult>();
    h.publication.mockReturnValueOnce(pending.promise);
    const operation = client.update(publicationId, {
      expectedRevision: 0,
      configuration,
    });
    const generation = h.service.runtime.getSessionGeneration();
    h.service.runtime.clearLocalSession();
    expect(h.service.runtime.getSessionGeneration()).not.toBe(generation);
    pending.resolve(response(200, owner));
    await expect(operation).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
    await expect(client.create(configuration, operationKey)).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
    expect(h.publication).toHaveBeenCalledTimes(1);
    expect(h.logout).not.toHaveBeenCalled();
    expect(h.service.runtime.getSnapshot().status).toBe("anonymous");
  });

  it("keeps an authority-unavailable login candidate out of Publication requests until authoritative manual recovery", async () => {
    const h = harness();
    h.me.mockResolvedValueOnce(
      response(503, { code: "AUTHORITY_UNAVAILABLE" }),
    );
    expect((await h.service.login(input)).ok).toBe(false);
    const unverifiedClient = h.client();
    expect(h.service.runtime.getSnapshot().status).toBe(
      "verification-unavailable",
    );
    await expect(
      unverifiedClient.create(configuration, operationKey),
    ).resolves.toEqual({ ok: false, error: { kind: "session" } });
    expect(h.publication).not.toHaveBeenCalled();
    expect(h.logout).not.toHaveBeenCalled();
    expect(h.refresh).toHaveBeenCalledTimes(1);
    expect((await h.service.retryVerification()).ok).toBe(true);
    expect(
      new Headers(h.me.mock.calls[1]![0].headers).get("authorization"),
    ).toBe("Bearer teacher-access");
    await expect(unverifiedClient.get(publicationId)).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
    await expect(h.client().get(publicationId)).resolves.toMatchObject({
      ok: true,
    });
    expect(h.refresh).toHaveBeenCalledTimes(1);
    expect(h.logout).not.toHaveBeenCalled();
  });

  it("passes protected authority 503 through without refresh, logout or loss of verified identity", async () => {
    const h = harness();
    const client = await h.signIn();
    const generation = h.service.runtime.getSessionGeneration();
    h.publication.mockResolvedValueOnce(
      response(503, { code: "AUTHORITY_UNAVAILABLE" }),
    );
    await expect(
      client.open(publicationId, { expectedRevision: 0 }),
    ).resolves.toEqual({
      ok: false,
      error: { kind: "api", status: 503, code: "AUTHORITY_UNAVAILABLE" },
    });
    expect(h.service.runtime.getSessionGeneration()).toBe(generation);
    expect(h.service.runtime.getSnapshot()).toMatchObject({
      status: "authenticated",
      user: { id: teacherId },
    });
    expect(h.refresh).toHaveBeenCalledTimes(1);
    expect(h.logout).not.toHaveBeenCalled();
  });

  it("single-flights concurrent Publication 401s and preserves generation, workspace and exact create replay", async () => {
    const h = harness();
    const client = await h.signIn();
    const generation = h.service.runtime.getSessionGeneration();
    const rotating = deferred<HttpTransportResult>();
    const refreshEntered = deferred<void>();
    h.refresh.mockImplementationOnce(() => {
      refreshEntered.resolve();
      return rotating.promise;
    });
    let initialCount = 0;
    const bothInitialRequests = deferred<void>();
    h.publication.mockImplementation(async (request) => {
      if (
        new Headers(request.headers).get("authorization") ===
        "Bearer teacher-access"
      ) {
        initialCount += 1;
        if (initialCount === 2) bothInitialRequests.resolve();
        return response(401, { code: "UNAUTHENTICATED" });
      }
      return response(request.method === "POST" ? 201 : 200, owner);
    });
    const create = client.create(configuration, operationKey);
    const detail = client.get(publicationId);
    await Promise.all([refreshEntered.promise, bothInitialRequests.promise]);
    expect(h.refresh).toHaveBeenCalledTimes(2); // Bootstrap plus one shared rotation.
    expect(h.service.runtime.getSnapshot().status).toBe("refreshing");
    rotating.resolve(credentials("renewed-teacher-access"));
    await expect(create).resolves.toEqual({ ok: true, value: owner });
    await expect(detail).resolves.toEqual({ ok: true, value: owner });
    const createAttempts = h.publication.mock.calls
      .map(([request]) => request)
      .filter((request) => request.method === "POST");
    expect(createAttempts).toHaveLength(2);
    expect(createAttempts.map((request) => request.body)).toEqual([
      JSON.stringify(configuration),
      JSON.stringify(configuration),
    ]);
    expect(
      createAttempts.map((request) =>
        new Headers(request.headers).get("Idempotency-Key"),
      ),
    ).toEqual([operationKey, operationKey]);
    expect(new Headers(createAttempts[1]!.headers).get("authorization")).toBe(
      "Bearer renewed-teacher-access",
    );
    expect(
      new Headers(h.me.mock.calls[1]![0].headers).get("authorization"),
    ).toBe("Bearer renewed-teacher-access");
    expect(h.service.runtime.getSessionGeneration()).toBe(generation);
    expect(h.service.runtime.getSnapshot()).toMatchObject({
      status: "authenticated",
      activeWorkspace: "TEACHING",
      user: { id: teacherId },
    });
    expect(h.refresh).toHaveBeenCalledTimes(2);
    expect(h.logout).not.toHaveBeenCalled();
  });

  it("latches refresh 503 for Publication 401s and only permits a fresh session to rotate again", async () => {
    const h = harness();
    const oldClient = await h.signIn();
    const generation = h.service.runtime.getSessionGeneration();
    h.publication.mockResolvedValue(response(401, { code: "UNAUTHENTICATED" }));
    h.refresh.mockResolvedValueOnce(
      response(503, { code: "AUTHORITY_UNAVAILABLE" }),
    );
    for (let index = 0; index < 3; index += 1) {
      await expect(oldClient.get(publicationId)).resolves.toEqual({
        ok: false,
        error: { kind: "unavailable", reason: "refresh" },
      });
    }
    expect(h.refresh).toHaveBeenCalledTimes(2);
    expect(h.logout).not.toHaveBeenCalled();
    expect(h.service.runtime.getSessionGeneration()).toBe(generation);
    expect(h.service.runtime.getSnapshot().status).toBe("authenticated");
    h.login.mockResolvedValueOnce(credentials("fresh-login-access"));
    expect((await h.service.login(input)).ok).toBe(true);
    expect(h.service.runtime.getSessionGeneration()).not.toBe(generation);
    await expect(oldClient.get(publicationId)).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
    h.refresh.mockResolvedValueOnce(credentials("fresh-rotated-access"));
    h.publication.mockImplementation(async (request) =>
      new Headers(request.headers).get("authorization") ===
      "Bearer fresh-rotated-access"
        ? response(200, owner)
        : response(401, { code: "UNAUTHENTICATED" }),
    );
    await expect(h.client().get(publicationId)).resolves.toMatchObject({
      ok: true,
    });
    expect(h.refresh).toHaveBeenCalledTimes(3);
  });

  it("bounds a stalled shared refresh, ignores late credentials and releases logout and a subsequent login", async () => {
    vi.useFakeTimers();
    const h = harness();
    const client = await h.signIn();
    const pending = deferred<HttpTransportResult>();
    const entered = deferred<HttpRequest>();
    h.publication.mockResolvedValue(response(401, { code: "UNAUTHENTICATED" }));
    h.refresh.mockImplementationOnce((request) => {
      entered.resolve(request);
      return pending.promise; // Deliberately ignores AbortSignal.
    });
    const request = client.get(publicationId);
    const refreshRequest = await entered.promise;
    await vi.advanceTimersByTimeAsync(9_999);
    expect(refreshRequest.signal?.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await expect(request).resolves.toEqual({
      ok: false,
      error: { kind: "unavailable", reason: "refresh" },
    });
    expect(refreshRequest.signal?.aborted).toBe(true);
    expect(h.service.runtime.getSnapshot().status).toBe("authenticated");
    expect((await h.service.logout()).ok).toBe(true);
    pending.resolve(credentials("obsolete-late-refresh-access"));
    expect((await h.service.login(input)).ok).toBe(true);
    h.publication.mockResolvedValue(response(200, owner));
    await expect(h.client().get(publicationId)).resolves.toMatchObject({
      ok: true,
    });
    expect(
      new Headers(h.publication.mock.calls.at(-1)![0].headers).get(
        "authorization",
      ),
    ).toBe("Bearer teacher-access");
    expect(h.refresh).toHaveBeenCalledTimes(2);
    expect(h.logout).toHaveBeenCalledTimes(1);
  });

  it("rejects an old Publication client when refresh authoritatively returns a different account", async () => {
    const h = harness();
    const client = await h.signIn();
    const generation = h.service.runtime.getSessionGeneration();
    h.publication.mockResolvedValueOnce(
      response(401, { code: "UNAUTHENTICATED" }),
    );
    h.refresh.mockResolvedValueOnce(credentials("other-refreshed-access"));
    h.me.mockResolvedValueOnce(profile(otherTeacherId));
    await expect(client.get(publicationId)).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
    expect(h.publication).toHaveBeenCalledTimes(1);
    expect(h.service.runtime.getSessionGeneration()).not.toBe(generation);
    expect(h.service.runtime.getSnapshot()).toMatchObject({
      status: "authenticated",
      activeWorkspace: "LEARNING",
      user: { id: otherTeacherId },
    });
    await expect(
      client.open(publicationId, { expectedRevision: 0 }),
    ).resolves.toEqual({ ok: false, error: { kind: "session" } });
    await expect(h.client().get(publicationId)).resolves.toMatchObject({
      ok: true,
    });
    expect(
      new Headers(h.publication.mock.calls[1]![0].headers).get("authorization"),
    ).toBe("Bearer other-refreshed-access");
  });

  it("exposes the same canonical generation for an injected runtime without inventing an authenticated executor", () => {
    const runtime = createSessionRuntime(createAccessTokenVault());
    let context!: AuthContextValue;
    function Probe() {
      context = useAuth();
      return <p>{context.user?.id ?? context.session.status}</p>;
    }
    render(
      <AuthProvider runtime={runtime}>
        <Probe />
      </AuthProvider>,
    );
    expect(context.getSessionGeneration()).toBe(runtime.getSessionGeneration());
    expect(context.authenticatedRequests).toBeNull();
    act(() => {
      runtime.establishAuthenticatedSession({
        accessToken: "injected-runtime-access",
        user: createAuthenticatedUser({
          id: teacherId,
          email: "teacher@gmail.com",
          username: "teacher",
          roles: ["TEACHER"],
        }),
      });
    });
    const generation = context.getSessionGeneration();
    expect(generation).toBe(runtime.getSessionGeneration());
    act(() => runtime.clearLocalSession());
    expect(context.getSessionGeneration()).toBe(runtime.getSessionGeneration());
    expect(context.getSessionGeneration()).not.toBe(generation);
    expect(context.authenticatedRequests).toBeNull();
    expect(screen.getByText("anonymous")).toBeInTheDocument();
  });
});
