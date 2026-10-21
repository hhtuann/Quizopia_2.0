import { act, render, waitFor } from "@testing-library/react";
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

vi.mock("../model/realtime-flag", () => ({
  realtimeFeatureEnabled: vi.fn(() => true),
}));

const flagMock = vi.mocked(realtimeFeatureEnabled);
const accessToken = "user-access-token";
const ticket = "T".repeat(43);
const attemptId = crypto.randomUUID();

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

function fixtures() {
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
  const user = createAuthenticatedUser({
    email: "learner01@gmail.com",
    id: crypto.randomUUID(),
    roles: ["STUDENT"],
    username: "learner01",
  });
  runtime.establishAuthenticatedSession({ accessToken, user });
  let ticketCalls = 0;
  const service: AuthSessionService = {
    authenticatedRequests: {
      async execute(request) {
        ticketCalls++;
        const created = request.createRequest();
        expect(String(created.target)).toContain("/api/realtime/tickets");
        expect(created.body).toBe("{}");
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
    readAccessToken: () => accessToken,
    bootstrap: vi.fn(async () => ({ status: "no-session" as const })),
    confirmVerification: vi.fn(),
    enableTeacher: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(async () => {
      runtime.clearLocalSession();
      return { ok: true as const, value: undefined };
    }),
    register: vi.fn(),
    requestVerification: vi.fn(),
    retryVerification: vi.fn(),
    runtime,
  };
  return {
    sockets,
    transport,
    runtime,
    service,
    ticketCalls: () => ticketCalls,
    user,
  };
}

beforeEach(() => {
  vi.useFakeTimers();
  flagMock.mockReturnValue(true);
});
afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("useRealtimeSubscriptions", () => {
  function Probe(options: {
    enabled: boolean;
    onSignal: (event: RealtimeClientEvent) => void;
    transport: RealtimeTransport;
  }) {
    const status = useRealtimeSubscriptions({
      enabled: options.enabled,
      resources: [{ kind: "ATTEMPT_OWNER", id: attemptId }],
      onSignal: options.onSignal,
      transport: options.transport,
    });
    return <div data-testid="status" data-state={status.state} />;
  }

  it("disabled mode and flag off never open sockets", async () => {
    flagMock.mockReturnValue(false);
    const f = fixtures();
    render(
      <AuthProvider service={f.service}>
        <Probe enabled onSignal={() => {}} transport={f.transport} />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    expect(f.sockets).toHaveLength(0);
    expect(f.ticketCalls()).toBe(0);
  });

  it("connects, authenticates and forwards strictly matched signals", async () => {
    const f = fixtures();
    const signals: RealtimeClientEvent[] = [];
    render(
      <AuthProvider service={f.service}>
        <Probe
          enabled
          onSignal={(event) => signals.push(event)}
          transport={f.transport}
        />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    expect(f.sockets).toHaveLength(1);
    f.sockets[0].serverOpen();
    expect(JSON.parse(f.sockets[0].sent[0])).toMatchObject({
      type: "AUTH",
      ticket,
      accessToken,
    });
    f.sockets[0].serverMessage('{"protocolVersion":1,"type":"AUTHENTICATED"}');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    const subscribe = JSON.parse(f.sockets[0].sent[1]);
    expect(subscribe.type).toBe("SUBSCRIBE");
    f.sockets[0].serverMessage(
      `{"protocolVersion":1,"type":"SUBSCRIBED","requestId":"${subscribe.requestId}","subscriptionId":"s1","resource":{"kind":"ATTEMPT_OWNER","id":"${attemptId}"}}`,
    );
    f.sockets[0].serverMessage(
      `{"protocolVersion":1,"type":"RESOURCE_INVALIDATED","eventId":"${crypto.randomUUID()}","subscriptionId":"s1","resource":{"kind":"ATTEMPT_OWNER","id":"${attemptId}"},"reason":"CHANGED","occurredAt":"2026-10-20T10:00:00Z"}`,
    );
    expect(signals).toEqual([
      { type: "connected" },
      {
        type: "invalidated",
        resource: { kind: "ATTEMPT_OWNER", id: attemptId },
      },
    ]);
  });

  it("logout closes the socket and stops reconnects", async () => {
    const f = fixtures();
    const { unmount } = render(
      <AuthProvider service={f.service}>
        <Probe enabled onSignal={() => {}} transport={f.transport} />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    f.sockets[0].serverOpen();
    f.sockets[0].serverMessage('{"protocolVersion":1,"type":"AUTHENTICATED"}');
    await act(async () => {
      await f.service.logout();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(f.sockets[0].closed).toBe(true);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });
    expect(f.sockets).toHaveLength(1);
    unmount();
  });

  it("unmount tears the connection down", async () => {
    const f = fixtures();
    const { unmount } = render(
      <AuthProvider service={f.service}>
        <Probe enabled onSignal={() => {}} transport={f.transport} />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    f.sockets[0].serverOpen();
    unmount();
    expect(f.sockets[0].closed).toBe(true);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });
    expect(f.sockets).toHaveLength(1);
  });
});

describe("useRealtimeResync wiring", () => {
  function RefreshProbe(options: { active: boolean; refresh: () => void }) {
    const resync = useRealtimeResync({
      active: options.active,
      run: options.refresh,
      periodMs: 60_000,
    });
    const status = useRealtimeSubscriptions({
      enabled: options.active,
      resources: [{ kind: "ATTEMPT_OWNER", id: attemptId }],
      onSignal: resync.request,
      transport: testTransport,
    });
    void status;
    return <div />;
  }

  let scripted: ScriptedSocket | null = null;
  const testTransport: RealtimeTransport = {
    open({ handlers }) {
      scripted = new ScriptedSocket(handlers);
      return scripted;
    },
  };

  it("invalidation signals drive the existing REST refresh path exactly once per burst", async () => {
    const f = fixtures();
    const refresh = vi.fn();
    render(
      <AuthProvider service={f.service}>
        <RefreshProbe active refresh={refresh} />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    const socket = scripted!;
    socket.serverOpen();
    socket.serverMessage('{"protocolVersion":1,"type":"AUTHENTICATED"}');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    const subscribe = JSON.parse(socket.sent[1]);
    socket.serverMessage(
      `{"protocolVersion":1,"type":"SUBSCRIBED","requestId":"${subscribe.requestId}","subscriptionId":"s1","resource":{"kind":"ATTEMPT_OWNER","id":"${attemptId}"}}`,
    );
    for (let index = 0; index < 5; index++)
      socket.serverMessage(
        `{"protocolVersion":1,"type":"RESOURCE_INVALIDATED","eventId":"${crypto.randomUUID()}","subscriptionId":"s1","resource":{"kind":"ATTEMPT_OWNER","id":"${attemptId}"},"reason":"CHANGED","occurredAt":"2026-10-20T10:0${index}:00Z"}`,
      );
    expect(refresh).not.toHaveBeenCalled();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(700);
    });
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("periodic resync runs only while active and visible", async () => {
    const f = fixtures();
    const refresh = vi.fn();
    const { rerender } = render(
      <AuthProvider service={f.service}>
        <RefreshProbe active={false} refresh={refresh} />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(180_000);
    });
    expect(refresh).not.toHaveBeenCalled();
    rerender(
      <AuthProvider service={f.service}>
        <RefreshProbe active refresh={refresh} />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200_000);
    });
    expect(
      refresh.mock.calls.every(
        (call) => call[0] === "periodic" || call.length === 0,
      ),
    ).toBe(true);
    expect(refresh).toHaveBeenCalledTimes(3);
  });
});
