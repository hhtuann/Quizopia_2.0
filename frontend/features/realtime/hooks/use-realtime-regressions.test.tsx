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
  it("sends the exact JWT that produced the ticket after a 401→refresh→rotation during the request", async () => {
    const sockets: ScriptedSocket[] = [];
    const transport: RealtimeTransport = {
      open({ url, subprotocol, handlers }) {
        expect(url).not.toContain("token");
        expect(subprotocol).toBe(REALTIME_SUBPROTOCOL);
        const socket = new ScriptedSocket(handlers);
        sockets.push(socket);
        return socket;
      },
    };
    // Simulated vault: the authenticated executor's 401→refresh→retry rotates the token
    // mid-request; the grant succeeds with the rotated JWT, which the server binds to the
    // issued ticket fingerprint.
    let vaultToken = "user-token-original";
    let grantToken: string | null = null;
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);
    runtime.establishAuthenticatedSession({
      accessToken: "user-token-original",
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
          vaultToken = "user-token-rotated";
          grantToken = vaultToken;
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
        async executeOnceWithAccessToken() {
          throw new Error("unused");
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
    const signals: RealtimeClientEvent[] = [];
    function Probe() {
      const status = useRealtimeSubscriptions({
        enabled: true,
        resources: [{ kind: "ATTEMPT_OWNER", id: attemptId }],
        onSignal: (signal) => signals.push(signal),
        transport,
      });
      return <div data-state={status.state} />;
    }
    render(
      <AuthProvider service={service}>
        <Probe />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    expect(sockets).toHaveLength(1);
    sockets[0].serverOpen();
    const auth = JSON.parse(sockets[0].sent[0]);
    expect(auth.type).toBe("AUTH");
    expect(auth.ticket).toBe(ticket);
    expect(auth.accessToken).toBe("user-token-rotated");
    expect(auth.accessToken).toBe(grantToken);
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
        async executeOnceWithAccessToken() {
          throw new Error("unused");
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
        async executeOnceWithAccessToken() {
          throw new Error("unused");
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
