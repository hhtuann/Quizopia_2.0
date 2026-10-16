import { describe, expect, it, vi } from "vitest";
import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import { createMediaApiClient } from "./media-api-client";
import { createMonitoringApiClient } from "./monitoring-api-client";

const attemptId = "11111111-1111-4111-8111-111111111111";
const sessionId = "22222222-2222-4222-8222-222222222222";
const grantId = "33333333-3333-4333-8333-333333333333";
const session = {
  id: sessionId,
  attemptId,
  publicationId: "44444444-4444-4444-8444-444444444444",
  classroomId: "55555555-5555-4555-8555-555555555555",
  studentId: "66666666-6666-4666-8666-666666666666",
  deadlineAt: "2026-10-16T12:00:00Z",
  status: "ACTIVE",
  createdAt: "2026-10-16T10:00:00Z",
  endedAt: null,
};

function response(value: unknown, status = 200): AuthenticatedRequestResult {
  return {
    kind: "response",
    response: {
      status,
      ok: status >= 200 && status < 300,
      headers: new Headers(),
      body: { kind: "json", value },
    },
  };
}

function harness(values: unknown[]) {
  const requests: Array<
    ReturnType<
      Parameters<AuthenticatedRequestExecutor["execute"]>[0]["createRequest"]
    >
  > = [];
  const execute = vi.fn<AuthenticatedRequestExecutor["execute"]>(
    async (call) => {
      requests.push(call.createRequest());
      return response(values.shift());
    },
  );
  const executor: AuthenticatedRequestExecutor = {
    execute,
    executeOnce: vi.fn(),
    executeOnceWithAccessToken: vi.fn(),
  };
  const options = {
    authenticatedRequests: executor,
    getSessionGeneration: () => 4,
    apiBaseUrl: "https://quizopia.example",
  };
  return {
    execute,
    requests,
    monitoring: createMonitoringApiClient(options),
    media: createMediaApiClient(options),
  };
}

describe("implemented Proctoring API inventory", () => {
  it("creates and discovers sessions using only the exact Attempt binding", async () => {
    const { requests, monitoring } = harness([session, session]);
    await monitoring.createSession(attemptId);
    await monitoring.findByAttempt(attemptId);
    expect(
      requests.map(({ method, target, body }) => [
        method,
        String(target),
        body,
      ]),
    ).toEqual([
      [
        "POST",
        "https://quizopia.example/api/proctoring/monitoring/sessions",
        JSON.stringify({ attemptId }),
      ],
      [
        "GET",
        `https://quizopia.example/api/proctoring/monitoring/sessions/by-attempt/${attemptId}`,
        undefined,
      ],
    ]);
    expect(requests[0]!.body).not.toContain("classroomId");
    expect(requests[0]!.body).not.toContain("role");
  });

  it("sends strict integer event JSON and bounded timeline pagination", async () => {
    const acknowledgment = {
      id: grantId,
      ordinal: 7,
      receivedAt: "2026-10-16T10:00:01Z",
    };
    const { requests, monitoring } = harness([
      acknowledgment,
      { items: [], nextAfter: null },
    ]);
    const event = {
      eventId: grantId,
      streamId: "77777777-7777-4777-8777-777777777777",
      sequence: 9,
      type: "ANSWER_CHANGED" as const,
      observedAt: null,
      questionNumber: 3,
      schemaVersion: 1 as const,
    };
    await monitoring.appendEvent(sessionId, event);
    await monitoring.timeline(sessionId, { after: 7, limit: 25 });
    expect(JSON.parse(String(requests[0]!.body))).toEqual(event);
    expect(
      String(requests[1]!.target).endsWith(
        `/sessions/${sessionId}/events?after=7&limit=25`,
      ),
    ).toBe(true);
  });

  it("keeps all media commands bodyless and role/room/query free", async () => {
    const grant = {
      id: grantId,
      url: "wss://livekit.example",
      token: "scoped-secret-token",
      leaseUntil: "2026-10-16T10:00:10Z",
      role: "STUDENT",
      technicalState: "GRANT_ISSUED",
    };
    const status = {
      id: grantId,
      leaseUntil: grant.leaseUntil,
      role: "STUDENT",
      status: "ACTIVE",
    };
    const { requests, media } = harness([grant, status, grant, status]);
    await media.createGrant(sessionId);
    await media.readGrant(sessionId, grantId);
    await media.renewGrant(sessionId, grantId);
    await media.revokeGrant(sessionId, grantId);
    expect(requests.map(({ method, body }) => [method, body])).toEqual([
      ["POST", undefined],
      ["GET", undefined],
      ["POST", undefined],
      ["POST", undefined],
    ]);
    for (const request of requests) {
      expect(String(request.target)).not.toContain("token");
      expect(String(request.target)).not.toContain("role=");
      expect(String(request.target)).not.toContain("?");
    }
  });

  it("fences requests after an account/session generation change", async () => {
    let generation: number | null = 1;
    const execute = vi.fn<AuthenticatedRequestExecutor["execute"]>();
    const executor: AuthenticatedRequestExecutor = {
      execute,
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    const client = createMonitoringApiClient({
      authenticatedRequests: executor,
      getSessionGeneration: () => generation,
    });
    generation = 2;
    await expect(client.createSession(attemptId)).resolves.toEqual({
      ok: false,
      error: { kind: "session" },
    });
    expect(execute).not.toHaveBeenCalled();
  });
});
