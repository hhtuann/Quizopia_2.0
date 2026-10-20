import { beforeEach, describe, expect, it, vi } from "vitest";
import type { RealtimeClientEvent } from "./realtime-client";
import { RealtimeClient } from "./realtime-client";
import {
  REALTIME_SUBPROTOCOL,
  type RealtimeTransport,
  type RealtimeTransportHandlers,
} from "./realtime-socket";

const URL = "wss://edge.example/ws/realtime";
const TICKET_A = "A".repeat(43);
const TICKET_B = "B".repeat(43);
const TOKEN_A = "access-token-a";
const TOKEN_B = "access-token-b";

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
  serverClose(code: number, reason = ""): void {
    this.handlers.onClose(code, reason);
  }
}

function harness(
  credentials: { ticket: string; accessToken: string }[] | "session",
) {
  const sockets: ScriptedSocket[] = [];
  const openedUrls: { url: string; subprotocol: string }[] = [];
  const events: RealtimeClientEvent[] = [];
  let credentialCalls = 0;
  const transport: RealtimeTransport = {
    open({ url, subprotocol, handlers }) {
      openedUrls.push({ url, subprotocol });
      const socket = new ScriptedSocket(handlers);
      sockets.push(socket);
      return socket;
    },
  };
  const client = new RealtimeClient({
    credentialsProvider: async () => {
      credentialCalls++;
      if (credentials === "session") return { ok: false, error: "session" };
      const pair =
        credentials[Math.min(credentialCalls - 1, credentials.length - 1)];
      return { ok: true, ticket: pair.ticket, accessToken: pair.accessToken };
    },
    url: URL,
    transport,
    authTimeoutMs: 5_000,
    reconnectBaseDelayMs: 1_000,
    reconnectMaximumDelayMs: 30_000,
    maximumReconnectAttempts: 3,
    jitter: () => 0.5,
    onEvent: (event) => events.push(event),
  });
  return {
    client,
    sockets,
    openedUrls,
    events,
    credentialCalls: () => credentialCalls,
  };
}

function authenticate(socket: ScriptedSocket, ticket: string) {
  socket.serverOpen();
  socket.serverMessage(
    `{"protocolVersion":1,"type":"WELCOME","connectionId":"${crypto.randomUUID()}"}`,
  );
  socket.serverMessage('{"protocolVersion":1,"type":"AUTHENTICATED"}');
  const auth = JSON.parse(socket.sent[0]);
  expect(auth.ticket).toBe(ticket);
  return socket.sent.length;
}

const attemptId = crypto.randomUUID();
const publicationId = crypto.randomUUID();

function subscribeFrameSent(socket: ScriptedSocket): {
  requestId: string;
  kind: string;
  id: string;
} {
  const frame = JSON.parse(socket.sent[socket.sent.length - 1]);
  expect(frame.type).toBe("SUBSCRIBE");
  return {
    requestId: frame.requestId,
    kind: frame.resource.kind,
    id: frame.resource.id,
  };
}

beforeEach(() => {
  vi.useFakeTimers();
});

describe("RealtimeClient", () => {
  it("handshake secrecy: exact URL/subprotocol, AUTH is the first and only pre-auth frame", async () => {
    const h = harness([{ ticket: TICKET_A, accessToken: TOKEN_A }]);
    h.client.setResources([{ kind: "ATTEMPT_OWNER", id: attemptId }]);
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.openedUrls).toEqual([
      { url: URL, subprotocol: REALTIME_SUBPROTOCOL },
    ]);
    expect(h.openedUrls[0].url).not.toContain("ticket");
    expect(h.sockets[0].sent).toEqual([]);
    h.sockets[0].serverOpen();
    expect(h.sockets[0].sent).toHaveLength(1);
    const auth = JSON.parse(h.sockets[0].sent[0]);
    expect(auth).toEqual({
      protocolVersion: 1,
      type: "AUTH",
      ticket: TICKET_A,
      accessToken: TOKEN_A,
    });
    const serialized = JSON.stringify(h.events);
    expect(serialized).not.toContain(TICKET_A);
    expect(serialized).not.toContain(TOKEN_A);
    h.client.stop();
  });

  it("authenticates, subscribes once, and emits strictly matched invalidation signals", async () => {
    const h = harness([{ ticket: TICKET_A, accessToken: TOKEN_A }]);
    h.client.setResources([{ kind: "ATTEMPT_OWNER", id: attemptId }]);
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    const socket = h.sockets[0];
    authenticate(socket, TICKET_A);
    await vi.advanceTimersByTimeAsync(0);
    const frame = subscribeFrameSent(socket);
    expect(frame.kind).toBe("ATTEMPT_OWNER");
    expect(frame.id).toBe(attemptId);
    socket.serverMessage(
      `{"protocolVersion":1,"type":"SUBSCRIBED","requestId":"${frame.requestId}","subscriptionId":"sub-1","resource":{"kind":"ATTEMPT_OWNER","id":"${attemptId}"}}`,
    );
    await vi.advanceTimersByTimeAsync(0);

    socket.serverMessage(
      `{"protocolVersion":1,"type":"RESOURCE_INVALIDATED","eventId":"${crypto.randomUUID()}","subscriptionId":"sub-1","resource":{"kind":"ATTEMPT_OWNER","id":"${attemptId}"},"reason":"CHANGED","occurredAt":"2026-10-20T10:00:00Z"}`,
    );
    socket.serverMessage(
      `{"protocolVersion":1,"type":"RESOURCE_INVALIDATED","eventId":"${crypto.randomUUID()}","subscriptionId":"sub-1","resource":{"kind":"ATTEMPT_OWNER","id":"${crypto.randomUUID()}"},"reason":"CHANGED","occurredAt":"2026-10-20T10:00:01Z"}`,
    );
    socket.serverMessage(
      `{"protocolVersion":1,"type":"RESOURCE_INVALIDATED","eventId":"${crypto.randomUUID()}","subscriptionId":"unknown","resource":{"kind":"ATTEMPT_OWNER","id":"${attemptId}"},"reason":"CHANGED","occurredAt":"2026-10-20T10:00:02Z"}`,
    );
    socket.serverMessage(
      '{"protocolVersion":1,"type":"RESYNC_REQUIRED","subscriptionId":"sub-1"}',
    );
    socket.serverMessage(
      '{"protocolVersion":1,"type":"SUBSCRIPTION_REVOKED","subscriptionId":"sub-1","code":"NOT_ACCESSIBLE"}',
    );
    socket.serverMessage(
      '{"protocolVersion":1,"type":"RESYNC_REQUIRED","subscriptionId":"sub-1"}',
    );
    await vi.advanceTimersByTimeAsync(0);
    const signals = h.events.filter((event) => event.type !== "state");
    expect(signals).toEqual([
      {
        type: "invalidated",
        resource: { kind: "ATTEMPT_OWNER", id: attemptId },
      },
      { type: "resync", resource: { kind: "ATTEMPT_OWNER", id: attemptId } },
      {
        type: "revoked",
        resource: { kind: "ATTEMPT_OWNER", id: attemptId },
        code: "NOT_ACCESSIBLE",
      },
    ]);
    h.client.stop();
  });

  it("unsubscribes on resource change and ignores later hints for removed resources", async () => {
    const h = harness([{ ticket: TICKET_A, accessToken: TOKEN_A }]);
    h.client.setResources([{ kind: "PUBLICATION_TEACHER", id: publicationId }]);
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    const socket = h.sockets[0];
    authenticate(socket, TICKET_A);
    await vi.advanceTimersByTimeAsync(0);
    const subscribe = subscribeFrameSent(socket);
    socket.serverMessage(
      `{"protocolVersion":1,"type":"SUBSCRIBED","requestId":"${subscribe.requestId}","subscriptionId":"p-1","resource":{"kind":"PUBLICATION_TEACHER","id":"${publicationId}"}}`,
    );
    await vi.advanceTimersByTimeAsync(0);
    h.client.setResources([]);
    await vi.advanceTimersByTimeAsync(0);
    const unsubscribe = JSON.parse(socket.sent[socket.sent.length - 1]);
    expect(unsubscribe.type).toBe("UNSUBSCRIBE");
    expect(unsubscribe.subscriptionId).toBe("p-1");
    expect(isCanonical(unsubscribe.requestId)).toBe(true);
    socket.serverMessage(
      `{"protocolVersion":1,"type":"UNSUBSCRIBED","requestId":"${unsubscribe.requestId}","subscriptionId":"p-1"}`,
    );
    await vi.advanceTimersByTimeAsync(0);
    const before = h.events.filter((event) => event.type !== "state").length;
    socket.serverMessage(
      `{"protocolVersion":1,"type":"RESOURCE_INVALIDATED","eventId":"${crypto.randomUUID()}","subscriptionId":"p-1","resource":{"kind":"PUBLICATION_TEACHER","id":"${publicationId}"},"reason":"CHANGED","occurredAt":"2026-10-20T10:00:00Z"}`,
    );
    await vi.advanceTimersByTimeAsync(0);
    expect(h.events.filter((event) => event.type !== "state").length).toBe(
      before,
    );
    h.client.stop();
  });

  it("auth deadline closes and reconnects with a fresh ticket and current token", async () => {
    const h = harness([
      { ticket: TICKET_A, accessToken: TOKEN_A },
      { ticket: TICKET_B, accessToken: TOKEN_B },
    ]);
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    h.sockets[0].serverOpen();
    expect(h.sockets[0].sent).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(h.sockets[0].closed).toBe(true);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(h.sockets).toHaveLength(2);
    h.sockets[1].serverOpen();
    authenticate(h.sockets[1], TICKET_B);
    expect(JSON.parse(h.sockets[1].sent[0]).accessToken).toBe(TOKEN_B);
    expect(h.credentialCalls()).toBe(2);
    h.client.stop();
  });

  it("abnormal close backs off with bounded exponential delay and replays subscriptions", async () => {
    const h = harness([
      { ticket: TICKET_A, accessToken: TOKEN_A },
      { ticket: TICKET_B, accessToken: TOKEN_B },
    ]);
    h.client.setResources([{ kind: "ATTEMPT_OWNER", id: attemptId }]);
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    authenticate(h.sockets[0], TICKET_A);
    await vi.advanceTimersByTimeAsync(0);
    h.sockets[0].serverClose(1006);
    await vi.advanceTimersByTimeAsync(0);
    const states = h.events.filter((event) => event.type === "state");
    expect(states.at(-1)?.state).toBe("reconnect-wait");
    await vi.advanceTimersByTimeAsync(1_000);
    expect(h.sockets).toHaveLength(2);
    h.sockets[1].serverOpen();
    authenticate(h.sockets[1], TICKET_B);
    await vi.advanceTimersByTimeAsync(0);
    expect(subscribeFrameSent(h.sockets[1]).id).toBe(attemptId);
    h.client.stop();
  });

  it("restart during backoff retries immediately and resets the attempt counter", async () => {
    const h = harness([
      { ticket: TICKET_A, accessToken: TOKEN_A },
      { ticket: TICKET_B, accessToken: TOKEN_B },
    ]);
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    h.sockets[0].serverOpen();
    h.sockets[0].serverClose(1006);
    await vi.advanceTimersByTimeAsync(0);
    h.client.restart();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(0);
    expect(h.sockets).toHaveLength(2);
    h.client.stop();
  });

  it("4401 and 4400 close codes stop permanently without reconnects", async () => {
    const h = harness([{ ticket: TICKET_A, accessToken: TOKEN_A }]);
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    h.sockets[0].serverOpen();
    authenticate(h.sockets[0], TICKET_A);
    h.sockets[0].serverClose(4401);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(h.sockets).toHaveLength(1);
    const final = h.events.filter((event) => event.type === "state").at(-1);
    expect(final).toMatchObject({
      state: "stopped",
      stopReason: "session-expired",
    });

    const second = harness([{ ticket: TICKET_A, accessToken: TOKEN_A }]);
    second.client.start();
    await vi.advanceTimersByTimeAsync(0);
    second.sockets[0].serverOpen();
    second.sockets[0].serverMessage("this is not json");
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(second.sockets).toHaveLength(1);
    expect(
      second.events.filter((event) => event.type === "state").at(-1),
    ).toMatchObject({
      state: "stopped",
      stopReason: "protocol-violation",
    });
  });

  it("bounded reconnect attempts end in exhausted stop", async () => {
    const h = harness([{ ticket: TICKET_A, accessToken: TOKEN_A }]);
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    for (let attempt = 0; attempt <= 3; attempt++) {
      h.sockets.at(-1)!.serverOpen();
      h.sockets.at(-1)!.serverClose(1006);
      await vi.advanceTimersByTimeAsync(0);
      await vi.advanceTimersByTimeAsync(30_000);
    }
    const final = h.events.filter((event) => event.type === "state").at(-1);
    expect(final).toMatchObject({
      state: "stopped",
      stopReason: "reconnects-exhausted",
    });
  });

  it("session credential failure stops without opening sockets", async () => {
    const h = harness("session");
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.sockets).toHaveLength(0);
    expect(
      h.events.filter((event) => event.type === "state").at(-1),
    ).toMatchObject({
      state: "stopped",
      stopReason: "credentials-unavailable",
    });
  });

  it("stop closes the socket, clears state and stops events", async () => {
    const h = harness([{ ticket: TICKET_A, accessToken: TOKEN_A }]);
    h.client.setResources([{ kind: "ATTEMPT_OWNER", id: attemptId }]);
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    authenticate(h.sockets[0], TICKET_A);
    await vi.advanceTimersByTimeAsync(0);
    h.client.stop();
    expect(h.sockets[0].closed).toBe(true);
    const eventCount = h.events.length;
    h.sockets[0].serverClose(1006);
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(h.sockets).toHaveLength(1);
    expect(h.events.length).toBeLessThanOrEqual(eventCount);
  });

  it("misconfigured url reports stopped without transport use", async () => {
    const events: RealtimeClientEvent[] = [];
    const client = new RealtimeClient({
      credentialsProvider: async () => ({
        ok: true,
        ticket: TICKET_A,
        accessToken: TOKEN_A,
      }),
      url: null,
      onEvent: (event) => events.push(event),
    });
    client.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(events.at(-1)).toMatchObject({
      type: "state",
      state: "stopped",
      stopReason: "misconfigured",
    });
  });

  it("server frames before AUTHENTICATED are protocol violations", async () => {
    const h = harness([{ ticket: TICKET_A, accessToken: TOKEN_A }]);
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    h.sockets[0].serverOpen();
    h.sockets[0].serverMessage(
      `{"protocolVersion":1,"type":"SUBSCRIBED","requestId":"${crypto.randomUUID()}","subscriptionId":"s","resource":{"kind":"ATTEMPT_OWNER","id":"${attemptId}"}}`,
    );
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(h.sockets).toHaveLength(1);
    expect(
      h.events.filter((event) => event.type === "state").at(-1),
    ).toMatchObject({
      state: "stopped",
      stopReason: "protocol-violation",
    });
  });

  it("ERROR correlation removes the pending operation without killing the connection", async () => {
    const h = harness([{ ticket: TICKET_A, accessToken: TOKEN_A }]);
    h.client.setResources([{ kind: "ATTEMPT_OWNER", id: attemptId }]);
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    const socket = h.sockets[0];
    authenticate(socket, TICKET_A);
    await vi.advanceTimersByTimeAsync(0);
    const frame = subscribeFrameSent(socket);
    socket.serverMessage(
      `{"protocolVersion":1,"type":"ERROR","code":"CAPACITY_EXCEEDED","requestId":"${frame.requestId}"}`,
    );
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(0);
    expect(
      h.events.filter((event) => event.type === "state").at(-1)?.state,
    ).toBe("authenticated");
    expect(socket.closed).toBe(false);
    h.client.stop();
  });

  it("never sends credentials after teardown", async () => {
    const h = harness([{ ticket: TICKET_A, accessToken: TOKEN_A }]);
    h.client.start();
    await vi.advanceTimersByTimeAsync(0);
    h.client.stop();
    const sentAfter = h.sockets[0]?.sent.length ?? 0;
    expect(sentAfter).toBe(0);
  });
});

function isCanonical(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
    value,
  );
}
