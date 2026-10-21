import { act, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../../auth/auth-provider";
import type { AuthSessionService } from "../../auth/session/auth-session-service";
import { createAccessTokenVault } from "../../auth/session/access-token-vault";
import { createSessionRuntime } from "../../auth/session/session-runtime";
import { createAuthenticatedUser } from "../../auth/model/authenticated-user";
import type { RealtimeClientEvent } from "../api/realtime-client";
import {
  REALTIME_SUBPROTOCOL,
  type RealtimeTransport,
  type RealtimeTransportHandlers,
} from "../api/realtime-socket";
import { realtimeFeatureEnabled } from "../model/realtime-flag";
import {
  useRealtimeResync,
  useRealtimeSubscriptions,
} from "./use-realtime-subscriptions";
import { createResyncScheduler } from "./resync-scheduler";

vi.mock("../model/realtime-flag", () => ({
  realtimeFeatureEnabled: vi.fn(() => true),
}));
const flagMock = vi.mocked(realtimeFeatureEnabled);

const attemptId = crypto.randomUUID();
const ticket = "T".repeat(43);

class ScriptedSocket {
  readonly sent: string[] = [];
  closed = false;
  constructor(readonly handlers: RealtimeTransportHandlers) {}
  send(data: string): void {
    if (!this.closed) this.sent.push(data);
  }
  close(): void {
    this.closed = true;
  }
  serverOpen(): void {
    this.handlers.onOpen();
  }
  serverMessage(text: string): void {
    this.handlers.onMessage(text);
  }
  serverClose(code = 1006): void {
    this.handlers.onClose(code, "");
  }
}

beforeEach(() => {
  vi.useFakeTimers();
  flagMock.mockReturnValue(true);
});
afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("ticket/AUTH token binding", () => {
  // The executor records the exact Bearer token each request was sent with; pinned
  // ticket issuance uses executeOnceWithAccessToken, so the recorded grant token is
  // provably the one the server bound the issued ticket to.
  function bindingHarness(input: {
    readonly initialVault: string;
    readonly rotateVaultTo?: string;
    readonly stalePinReturns401?: boolean;
  }) {
    const sockets: ScriptedSocket[] = [];
    const transport: RealtimeTransport = {
      open({ url, subprotocol, handlers }) {
        expect(url).not.toContain("token");
        expect(url).not.toContain("?");
        expect(subprotocol).toBe(REALTIME_SUBPROTOCOL);
        const socket = new ScriptedSocket(handlers);
        sockets.push(socket);
        return socket;
      },
    };
    let vaultToken = input.initialVault;
    const grants: { token: string; ticket: string }[] = [];
    let pinCalls = 0;
    const runtime = createSessionRuntime(createAccessTokenVault());
    runtime.establishAuthenticatedSession({
      accessToken: input.initialVault,
      user: createAuthenticatedUser({
        email: "learner01@gmail.com",
        id: crypto.randomUUID(),
        roles: ["STUDENT"],
        username: "learner01",
      }),
    });
    const grantResponse = (token: string) => {
      const issued = `T${grants.length}${token.slice(-1)}`.padEnd(43, "x");
      grants.push({ token, ticket: issued });
      return {
        kind: "response",
        response: {
          status: 201,
          body: {
            kind: "json",
            value: { ticket: issued, expiresInSeconds: 30, protocolVersion: 1 },
          },
        },
      };
    };
    const service: AuthSessionService = {
      authenticatedRequests: {
        async execute() {
          // Refresh-aware fallback: rotates the vault and succeeds (its ticket is
          // discarded by the bound flow; only the vault refresh matters).
          if (input.rotateVaultTo !== undefined)
            vaultToken = input.rotateVaultTo;
          return grantResponse(vaultToken) as never;
        },
        async executeOnce() {
          throw new Error("unused");
        },
        async executeOnceWithAccessToken(
          _request: unknown,
          accessToken: string,
        ) {
          pinCalls++;
          if (input.stalePinReturns401 && pinCalls === 1) {
            // A concurrent request rotates the vault while the stale pin is rejected.
            if (input.rotateVaultTo !== undefined)
              vaultToken = input.rotateVaultTo;
            return {
              kind: "response",
              response: {
                status: 401,
                body: { kind: "json", value: { code: "X" } },
              },
            } as never;
          }
          // The pin is sent verbatim; a concurrent rotation right after this grant
          // must not change what the AUTH frame carries.
          const result = grantResponse(accessToken);
          if (input.rotateVaultTo !== undefined && !input.stalePinReturns401) {
            vaultToken = input.rotateVaultTo;
          }
          return result as never;
        },
      },
      readAccessToken: () => vaultToken,
      bootstrap: vi.fn(async () => ({ status: "no-session" as const })),
      confirmVerification: vi.fn(),
      enableTeacher: vi.fn(),
      login: vi.fn(),
      logout: vi.fn(async () => ({ ok: true as const, value: undefined })),
      register: vi.fn(),
      requestVerification: vi.fn(),
      retryVerification: vi.fn(),
      runtime,
    };
    return { sockets, transport, service, grants: () => grants };
  }

  function BindingProbe(props: {
    transport: RealtimeTransport;
    onSignal: (event: RealtimeClientEvent) => void;
  }) {
    const status = useRealtimeSubscriptions({
      enabled: true,
      resources: [{ kind: "ATTEMPT_OWNER", id: attemptId }],
      onSignal: props.onSignal,
      transport: props.transport,
    });
    return <div data-state={status.state} />;
  }

  it("AUTH uses the pinned JWT A even when the vault rotates to B after ticket issuance", async () => {
    const h = bindingHarness({
      initialVault: "jwt-A",
      rotateVaultTo: "jwt-B",
    });
    const signals: RealtimeClientEvent[] = [];
    render(
      <AuthProvider service={h.service}>
        <BindingProbe
          transport={h.transport}
          onSignal={(signal) => signals.push(signal)}
        />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    expect(h.sockets).toHaveLength(1);
    h.sockets[0].serverOpen();
    const auth = JSON.parse(h.sockets[0].sent[0]);
    expect(auth.type).toBe("AUTH");
    expect(auth.accessToken).toBe("jwt-A");
    // The single grant was issued under exactly the pinned JWT A.
    expect(h.grants()).toEqual([{ token: "jwt-A", ticket: auth.ticket }]);
    expect(JSON.stringify(signals)).not.toContain("jwt-A");
    expect(JSON.stringify(signals)).not.toContain("jwt-B");
    expect(JSON.stringify(h.sockets[0].sent[0])).not.toContain("jwt-B");
  });

  it("a stale pinned JWT recovers through one refresh fallback and re-pins to the fresh JWT", async () => {
    const h = bindingHarness({
      initialVault: "jwt-A",
      rotateVaultTo: "jwt-C",
      stalePinReturns401: true,
    });
    render(
      <AuthProvider service={h.service}>
        <BindingProbe transport={h.transport} onSignal={() => {}} />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    expect(h.sockets).toHaveLength(1);
    h.sockets[0].serverOpen();
    const auth = JSON.parse(h.sockets[0].sent[0]);
    expect(auth.type).toBe("AUTH");
    // The final AUTH pairs a ticket issued under the fresh JWT C with JWT C itself; the
    // fallback's own grant is discarded and only the re-pinned ticket is used.
    expect(auth.accessToken).toBe("jwt-C");
    const grants = h.grants();
    expect(grants.every((grant) => grant.token === "jwt-C")).toBe(true);
    expect(grants.filter((grant) => grant.token === "jwt-C").length).toBe(2);
    expect(auth.ticket).toBe(grants.at(-1)?.ticket);
    expect(auth.ticket).not.toBe(grants[0]?.ticket);
  });
});

describe("reconnection and recovery reconciliation", () => {
  function harness() {
    const sockets: ScriptedSocket[] = [];
    const transport: RealtimeTransport = {
      open({ handlers }) {
        const socket = new ScriptedSocket(handlers);
        sockets.push(socket);
        return socket;
      },
    };
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);
    runtime.establishAuthenticatedSession({
      accessToken: "user-access-token",
      user: createAuthenticatedUser({
        email: "learner01@gmail.com",
        id: crypto.randomUUID(),
        roles: ["STUDENT"],
        username: "learner01",
      }),
    });
    const service: AuthSessionService = {
      authenticatedRequests: {
        async execute() {
          return {
            kind: "response",
            response: {
              status: 201,
              body: {
                kind: "json",
                value: { ticket, expiresInSeconds: 30, protocolVersion: 1 },
              },
            },
          } as never;
        },
        async executeOnce() {
          throw new Error("unused");
        },
        async executeOnceWithAccessToken(request: unknown) {
          return this.execute(request as never);
        },
      },
      readAccessToken: () => "user-access-token",
      bootstrap: vi.fn(async () => ({ status: "no-session" as const })),
      confirmVerification: vi.fn(),
      enableTeacher: vi.fn(),
      login: vi.fn(),
      logout: vi.fn(async () => ({ ok: true as const, value: undefined })),
      register: vi.fn(),
      requestVerification: vi.fn(),
      retryVerification: vi.fn(),
      runtime,
    };
    return { sockets, transport, service };
  }

  function Probe(props: {
    transport: RealtimeTransport;
    onSignal: (event: RealtimeClientEvent) => void;
  }) {
    const status = useRealtimeSubscriptions({
      enabled: true,
      resources: [{ kind: "ATTEMPT_OWNER", id: attemptId }],
      onSignal: props.onSignal,
      transport: props.transport,
    });
    return <div data-state={status.state} />;
  }

  it("missed hints reconcile: every reconnection emits a connected signal even without invalidation", async () => {
    const h = harness();
    const signals: RealtimeClientEvent[] = [];
    render(
      <AuthProvider service={h.service}>
        <Probe
          transport={h.transport}
          onSignal={(signal) => signals.push(signal)}
        />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    h.sockets[0].serverOpen();
    h.sockets[0].serverMessage('{"protocolVersion":1,"type":"AUTHENTICATED"}');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(signals).toEqual([{ type: "connected" }]);

    h.sockets[0].serverClose(1006);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
      await vi.advanceTimersByTimeAsync(2_000);
    });
    expect(h.sockets).toHaveLength(2);
    h.sockets[1].serverOpen();
    h.sockets[1].serverMessage('{"protocolVersion":1,"type":"AUTHENTICATED"}');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(signals).toEqual([{ type: "connected" }, { type: "connected" }]);
  });

  it("visibility and online recovery request reconciliation on the live connection", async () => {
    const h = harness();
    const signals: RealtimeClientEvent[] = [];
    render(
      <AuthProvider service={h.service}>
        <Probe
          transport={h.transport}
          onSignal={(signal) => signals.push(signal)}
        />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    h.sockets[0].serverOpen();
    h.sockets[0].serverMessage('{"protocolVersion":1,"type":"AUTHENTICATED"}');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(signals).toHaveLength(1);
    await act(async () => {
      document.dispatchEvent(new Event("visibilitychange"));
      window.dispatchEvent(new Event("online"));
    });
    expect(signals.filter((signal) => signal.type === "connected").length).toBe(
      3,
    );
    expect(h.sockets).toHaveLength(1);
  });
});

describe("in-flight REST guard", () => {
  it("slow unresolved refreshes never run concurrently; follow-ups coalesce", async () => {
    let resolveRun!: () => void;
    let active = 0;
    let peak = 0;
    const run = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          active++;
          peak = Math.max(peak, active);
          resolveRun = () => {
            active--;
            resolve();
          };
        }),
    );
    const scheduler = createResyncScheduler(run, {
      debounceMs: 50,
      minimumIntervalMs: 0,
    });
    scheduler.request();
    scheduler.tick();
    scheduler.request();
    await vi.advanceTimersByTimeAsync(60);
    expect(run).toHaveBeenCalledTimes(1);
    expect(peak).toBe(1);
    scheduler.tick();
    scheduler.request();
    await vi.advanceTimersByTimeAsync(60);
    expect(run).toHaveBeenCalledTimes(1);
    await act(async () => {
      resolveRun();
    });
    await vi.advanceTimersByTimeAsync(100);
    expect(run).toHaveBeenCalledTimes(2);
    expect(peak).toBe(1);
    scheduler.dispose();
  });

  it("the hook returns the view's real refresh promise to the scheduler", async () => {
    let resolveRefresh!: () => void;
    const refreshes: Promise<void>[] = [];
    function ResyncProbe() {
      const resync = useRealtimeResync({
        active: true,
        run: () =>
          new Promise<void>((resolve) => {
            resolveRefresh = resolve;
            refreshes.push(new Promise<void>(() => {}));
          }),
        periodMs: 10_000,
      });
      const status = useRealtimeSubscriptions({
        enabled: true,
        resources: [{ kind: "ATTEMPT_OWNER", id: attemptId }],
        onSignal: resync.request,
        transport: probeTransport,
      });
      return <div data-state={status.state} />;
    }

    const sockets: ScriptedSocket[] = [];
    const probeTransport: RealtimeTransport = {
      open({ handlers }) {
        const socket = new ScriptedSocket(handlers);
        sockets.push(socket);
        return socket;
      },
    };
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);
    runtime.establishAuthenticatedSession({
      accessToken: "user-access-token",
      user: createAuthenticatedUser({
        email: "learner01@gmail.com",
        id: crypto.randomUUID(),
        roles: ["STUDENT"],
        username: "learner01",
      }),
    });
    const service: AuthSessionService = {
      authenticatedRequests: {
        async execute() {
          return {
            kind: "response",
            response: {
              status: 201,
              body: {
                kind: "json",
                value: { ticket, expiresInSeconds: 30, protocolVersion: 1 },
              },
            },
          } as never;
        },
        async executeOnce() {
          throw new Error("unused");
        },
        async executeOnceWithAccessToken(request: unknown) {
          return this.execute(request as never);
        },
      },
      readAccessToken: () => "user-access-token",
      bootstrap: vi.fn(async () => ({ status: "no-session" as const })),
      confirmVerification: vi.fn(),
      enableTeacher: vi.fn(),
      login: vi.fn(),
      logout: vi.fn(async () => ({ ok: true as const, value: undefined })),
      register: vi.fn(),
      requestVerification: vi.fn(),
      retryVerification: vi.fn(),
      runtime,
    };
    render(
      <AuthProvider service={service}>
        <ResyncProbe />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    sockets[0].serverOpen();
    sockets[0].serverMessage('{"protocolVersion":1,"type":"AUTHENTICATED"}');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });
    // The connected signal triggered exactly one refresh and the guard holds until it
    // resolves; a second signal while unresolved must not start another one.
    expect(refreshes.length).toBe(1);
    window.dispatchEvent(new Event("online"));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });
    expect(refreshes.length).toBe(1);
    await act(async () => {
      resolveRefresh();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_600);
    });
    expect(refreshes.length).toBe(2);
  });
});
