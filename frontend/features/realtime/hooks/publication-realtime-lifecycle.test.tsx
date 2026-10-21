import { act, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../../auth/auth-provider";
import type { AuthSessionService } from "../../auth/session/auth-session-service";
import { createAccessTokenVault } from "../../auth/session/access-token-vault";
import { createSessionRuntime } from "../../auth/session/session-runtime";
import { createAuthenticatedUser } from "../../auth/model/authenticated-user";
import type {
  Configuration,
  OwnerView,
} from "../../assessments/api/publication-contract.generated";
import type { PublicationApiClient } from "../../assessments/api/publication-api-client";
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
const flagMock = vi.mocked(realtimeFeatureEnabled);

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

const { PublicationDetailScreen } =
  await import("../../assessments/components/publication-detail-screen");

const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const ticket = "T".repeat(43);
const configuration: Configuration = {
  quizId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  quizVersionId: "9bd4c564-3c27-4e6d-91aa-a004334aa8f7",
  versionNumber: 3,
  title: "Immutable network assessment",
  description: "Original source",
  audience: "PUBLIC",
  classroomId: null,
  startAt: "2026-10-16T08:00:00Z",
  endAt: "2026-10-16T09:00:00Z",
  durationMinutes: 40,
  monitoringChoice: "DISABLED",
  maxAttempts: 2,
};

function view(state: "DRAFT" | "OPEN" | "CLOSED", revision: number): OwnerView {
  return {
    publicationId,
    configuration,
    state,
    revision,
    createdAt: "2026-10-16T07:00:00Z",
    openedAt: state === "DRAFT" ? null : "2026-10-16T07:30:00Z",
    privateLocator: "A".repeat(43),
  };
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

beforeEach(() => {
  vi.useFakeTimers();
  flagMock.mockReturnValue(true);
});
afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("PublicationDetailScreen realtime lifecycle", () => {
  it("subscribes only while OPEN and follows DRAFT→OPEN→CLOSED without reconnecting", async () => {
    const sockets: ScriptedSocket[] = [];
    scripted.sockets.length = 0;
    scripted.transport = {
      open({ url, subprotocol, handlers }) {
        expect(url.endsWith("/ws/realtime")).toBe(true);
        expect(subprotocol).toBe(REALTIME_SUBPROTOCOL);
        const socket = new ScriptedSocket(handlers);
        sockets.push(socket);
        scripted.sockets.push(socket);
        return socket;
      },
    };
    const views = [
      view("DRAFT", 0),
      view("OPEN", 1),
      view("CLOSED", 2),
      view("CLOSED", 2),
    ];
    let read = 0;
    const client = {
      get: vi.fn(async () => {
        const value = views[Math.min(read, views.length - 1)];
        read++;
        return { ok: true as const, value };
      }),
      update: vi.fn(),
      open: vi.fn(),
      extend: vi.fn(),
      close: vi.fn(),
    } as unknown as PublicationApiClient;
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);
    runtime.establishAuthenticatedSession({
      accessToken: "user-access-token",
      user: createAuthenticatedUser({
        email: "teacher01@gmail.com",
        id: crypto.randomUUID(),
        roles: ["TEACHER"],
        username: "teacher01",
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
      logout: vi.fn(),
      register: vi.fn(),
      requestVerification: vi.fn(),
      retryVerification: vi.fn(),
      runtime,
    };

    render(
      <AuthProvider service={service}>
        <PublicationDetailScreen
          client={client}
          publicationId={publicationId}
        />
      </AuthProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    // Mount read returned DRAFT: connected, but no subscription yet.
    expect(sockets).toHaveLength(1);
    sockets[0].serverOpen();
    sockets[0].serverMessage('{"protocolVersion":1,"type":"AUTHENTICATED"}');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(700);
    });
    // The connected signal refreshed to OPEN (read #2): SUBSCRIBE must now be sent.
    expect(read).toBeGreaterThanOrEqual(2);
    const subscribe = sockets[0].sent.find(
      (frame) => JSON.parse(frame).type === "SUBSCRIBE",
    );
    expect(subscribe).toBeDefined();
    const subscribeFrame = JSON.parse(subscribe as string);
    expect(subscribeFrame.resource).toEqual({
      kind: "PUBLICATION_TEACHER",
      id: publicationId,
    });
    // Server acknowledges so the subscription becomes active and unsubscribe-able.
    sockets[0].serverMessage(
      `{"protocolVersion":1,"type":"SUBSCRIBED","requestId":"${subscribeFrame.requestId}","subscriptionId":"p-1","resource":{"kind":"PUBLICATION_TEACHER","id":"${publicationId}"}}`,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    // Visibility recovery refreshes to CLOSED (read #3): UNSUBSCRIBE over the same socket.
    document.dispatchEvent(new Event("visibilitychange"));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    const unsubscribe = sockets[0].sent.find(
      (frame) => JSON.parse(frame).type === "UNSUBSCRIBE",
    );
    expect(unsubscribe).toBeDefined();
    expect(JSON.parse(unsubscribe as string).subscriptionId).toBe("p-1");
    expect(sockets).toHaveLength(1);
    expect(sockets[0].closed).toBe(false);
    expect(client.get).toHaveBeenCalledTimes(read);
  });
});
