import { describe, expect, it, vi } from "vitest";
import type { AuthenticatedRequestExecutor } from "../../../lib/api/authenticated-request";
import { createRealtimeTicketRequester } from "./realtime-ticket-client";

function executor(
  respond: (request: {
    createRequest: () => Record<string, unknown>;
  }) => Promise<{
    kind: string;
    response?: { status: number; body: { kind: string; value: unknown } };
  }>,
): AuthenticatedRequestExecutor {
  return {
    async execute(request) {
      return respond(request as never) as never;
    },
    async executeOnce(request) {
      return respond(request as never) as never;
    },
    async executeOnceWithAccessToken() {
      throw new Error("unused");
    },
  };
}

const ticketBody = {
  ticket: "T".repeat(43),
  expiresInSeconds: 30,
  protocolVersion: 1,
};

describe("createRealtimeTicketRequester", () => {
  it("posts the exact empty object to the public ticket route", async () => {
    let seen: Record<string, unknown> | null = null;
    const requester = createRealtimeTicketRequester({
      authenticatedRequests: executor(async (request) => {
        seen = request.createRequest();
        return {
          kind: "response",
          response: { status: 201, body: { kind: "json", value: ticketBody } },
        };
      }),
    });
    const ticket = await requester.requestTicket();
    expect(ticket).toEqual({
      ok: true,
      ticket: "T".repeat(43),
      expiresInSeconds: 30,
    });
    expect(seen!.method).toBe("POST");
    expect(seen!.target).toContain("/api/realtime/tickets");
    expect(seen!.target).not.toContain("?");
    expect(seen!.body).toBe("{}");
    expect((seen!.headers as Record<string, string>)["content-type"]).toBe(
      "application/json",
    );
  });

  it("maps authentication, rate limit, authority and transport failures", async () => {
    const cases: { status: number; expected: string }[] = [
      { status: 401, expected: "session" },
      { status: 403, expected: "session" },
      { status: 429, expected: "rate-limited" },
      { status: 503, expected: "authority-unavailable" },
      { status: 500, expected: "invalid-response" },
    ];
    for (const item of cases) {
      const requester = createRealtimeTicketRequester({
        authenticatedRequests: executor(async () => ({
          kind: "response",
          response: {
            status: item.status,
            body: { kind: "json", value: { code: "X" } },
          },
        })),
      });
      const ticket = await requester.requestTicket();
      expect(ticket).toMatchObject({
        ok: false,
        error: { kind: item.expected },
      });
    }
    const unavailable = createRealtimeTicketRequester({
      authenticatedRequests: executor(async () => ({
        kind: "transport-error",
        error: { kind: "network" },
      })),
    });
    expect(await unavailable.requestTicket()).toMatchObject({
      ok: false,
      error: { kind: "unavailable" },
    });
  });

  it("rejects malformed grant bodies", async () => {
    for (const value of [
      { ticket: "short", expiresInSeconds: 30, protocolVersion: 1 },
      { ticket: "T".repeat(43), expiresInSeconds: 0, protocolVersion: 1 },
      { ticket: "T".repeat(43), expiresInSeconds: 30, protocolVersion: 2 },
      { ticket: "T".repeat(43), expiresInSeconds: 30 },
    ]) {
      const requester = createRealtimeTicketRequester({
        authenticatedRequests: executor(async () => ({
          kind: "response",
          response: { status: 201, body: { kind: "json", value } },
        })),
      });
      expect(await requester.requestTicket()).toMatchObject({
        ok: false,
        error: { kind: "invalid-response" },
      });
    }
  });

  it("abort signals surface as unavailable without executing", async () => {
    const execute = vi.fn();
    const requester = createRealtimeTicketRequester({
      authenticatedRequests: {
        execute,
        executeOnce: execute,
        async executeOnceWithAccessToken() {
          throw new Error("unused");
        },
      } as unknown as AuthenticatedRequestExecutor,
    });
    const controller = new AbortController();
    controller.abort();
    const ticket = await requester.requestTicket(controller.signal);
    expect(ticket).toMatchObject({ ok: false, error: { kind: "unavailable" } });
  });
});
