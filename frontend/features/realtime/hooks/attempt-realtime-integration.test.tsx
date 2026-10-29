import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../../auth/auth-provider";
import type { AuthSessionService } from "../../auth/session/auth-session-service";
import { createAccessTokenVault } from "../../auth/session/access-token-vault";
import { createSessionRuntime } from "../../auth/session/session-runtime";
import { createAuthenticatedUser } from "../../auth/model/authenticated-user";
import { NO_ATTEMPT_MONITORING } from "../../attempts/model/monitoring-boundary";
import { AttemptEphemeralProvider } from "../../attempts/hooks/attempt-ephemeral-context";
import type {
  AttemptApiClient,
  AttemptResult,
} from "../../attempts/api/attempt-api-client";
import type { AttemptView } from "../../attempts/api/attempt-contract.generated";
import {
  REALTIME_SUBPROTOCOL,
  type RealtimeTransport,
  type RealtimeTransportHandlers,
} from "../api/realtime-socket";
import { realtimeFeatureEnabled } from "../model/realtime-flag";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));
vi.mock("../model/realtime-flag", () => ({
  realtimeFeatureEnabled: vi.fn(() => true),
}));

const scripted = vi.hoisted(() => ({
  sockets: [] as {
    sent: string[];
    closed: boolean;
    serverOpen(): void;
    serverMessage(text: string): void;
  }[],
  transport: null as import("../api/realtime-socket").RealtimeTransport | null,
}));

vi.mock("../api/realtime-socket", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("../api/realtime-socket")>();
  return {
    ...actual,
    get browserWebSocketTransport() {
      return scripted.transport ?? actual.browserWebSocketTransport;
    },
  };
});

const flagMock = vi.mocked(realtimeFeatureEnabled);

const { AttemptScreen } =
  await import("../../attempts/components/student-assessment-pages");

const attemptId = crypto.randomUUID();
const publicationId = crypto.randomUUID();
const accessToken = "user-access-token";
const ticket = "T".repeat(43);

function attemptView(
  state: "ACTIVE" | "SUBMITTED",
  revision: string,
): AttemptView {
  return {
    attemptId,
    publicationId,
    ordinal: "1",
    state,
    startedAt: new Date(Date.now() - 60_000).toISOString(),
    deadlineAt: new Date(Date.now() + 3_600_000).toISOString(),
    durationMinutes: 30,
    revision,
    serverNow: new Date().toISOString(),
    questions: [
      { number: 1, type: "NUMERIC_FILL", stemMarkdown: "First", options: [] },
      { number: 2, type: "NUMERIC_FILL", stemMarkdown: "Second", options: [] },
    ],
    answers: [
      { questionNumber: 1, answer: { type: "NUMERIC_FILL", value: null } },
      { questionNumber: 2, answer: { type: "NUMERIC_FILL", value: null } },
    ],
    closure: null,
  } as unknown as AttemptView;
}

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
}

function fixtures(
  initial: AttemptView,
  getAttempt: () => AttemptResult<AttemptView>,
) {
  const sockets: ScriptedSocket[] = [];
  scripted.sockets.length = 0;
  scripted.transport = {
    open({ url, subprotocol, handlers }) {
      expect(url.endsWith("/ws/realtime")).toBe(true);
      expect(url).not.toContain("?");
      expect(subprotocol).toBe(REALTIME_SUBPROTOCOL);
      const socket = new ScriptedSocket(handlers);
      sockets.push(socket);
      scripted.sockets.push(socket);
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
  const client: AttemptApiClient = {
    async getAttempt(): Promise<AttemptResult<AttemptView>> {
      return getAttempt();
    },
  } as unknown as AttemptApiClient;
  const service: AuthSessionService = {
    authenticatedRequests: {
      async execute(request) {
        const created = request.createRequest();
        expect(String(created.target)).toContain("/api/realtime/tickets");
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
    logout: vi.fn(async () => ({ ok: true as const, value: undefined })),
    register: vi.fn(),
    requestVerification: vi.fn(),
    retryVerification: vi.fn(),
    runtime,
  };
  return { sockets, client, service };
}

beforeEach(() => {
  vi.useFakeTimers();
  flagMock.mockReturnValue(true);
});
afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("AttemptScreen realtime integration", () => {
  it("invalidation hints refresh authoritative state while preserving unsaved drafts", async () => {
    const initial = attemptView("ACTIVE", "0");
    let fetchCount = 0;
    const { sockets, client, service } = fixtures(initial, () => {
      fetchCount++;
      return { ok: true, value: attemptView("ACTIVE", "1") };
    });
    render(
      <AuthProvider service={service}>
        <AttemptEphemeralProvider>
          <AttemptScreen
            attempt={initial}
            client={client}
            monitoring={NO_ATTEMPT_MONITORING}
          />
        </AttemptEphemeralProvider>
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(sockets).toHaveLength(1);
    sockets[0].serverOpen();
    sockets[0].serverMessage('{"protocolVersion":1,"type":"AUTHENTICATED"}');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    const subscribe = JSON.parse(sockets[0].sent[1]);
    expect(subscribe.resource).toEqual({
      kind: "ATTEMPT_OWNER",
      id: attemptId,
    });
    sockets[0].serverMessage(
      `{"protocolVersion":1,"type":"SUBSCRIBED","requestId":"${subscribe.requestId}","subscriptionId":"s1","resource":{"kind":"ATTEMPT_OWNER","id":"${attemptId}"}}`,
    );

    // Both questions render their answer inputs continuously; edit question 1.
    const field = screen.getAllByRole("textbox")[0]!;
    fireEvent.change(field, { target: { value: "42" } });
    expect((field as HTMLInputElement).value).toContain("42");

    sockets[0].serverMessage(
      `{"protocolVersion":1,"type":"RESOURCE_INVALIDATED","eventId":"${crypto.randomUUID()}","subscriptionId":"s1","resource":{"kind":"ATTEMPT_OWNER","id":"${attemptId}"},"reason":"CHANGED","occurredAt":"2026-10-20T10:00:00Z"}`,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });
    expect(fetchCount).toBeGreaterThanOrEqual(1);
    const refreshed = screen.getAllByRole("textbox")[0]!;
    expect((refreshed as HTMLInputElement).value).toContain("42");
  });

  it("flag off keeps realtime fully absent while the attempt still works", async () => {
    flagMock.mockReturnValue(false);
    const initial = attemptView("ACTIVE", "0");
    const { sockets, client, service } = fixtures(initial, () => ({
      ok: true,
      value: initial,
    }));
    render(
      <AuthProvider service={service}>
        <AttemptEphemeralProvider>
          <AttemptScreen
            attempt={initial}
            client={client}
            monitoring={NO_ATTEMPT_MONITORING}
          />
        </AttemptEphemeralProvider>
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(sockets).toHaveLength(0);
    expect(screen.queryByText(/Live updates:/)).toBeNull();
  });
});
