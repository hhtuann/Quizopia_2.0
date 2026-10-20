import { z } from "zod";
import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import { publicApiTarget } from "../../../lib/api/public-api";

/**
 * One-time realtime ticket grant through the public edge. The exact empty JSON object is
 * the whole request; the ticket is returned in memory only and never persisted or logged.
 */

export const REALTIME_TICKET_TIMEOUT = 10_000;

const ticketResponseSchema = z
  .object({
    ticket: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
    expiresInSeconds: z.number().int().min(1).max(3_600),
    protocolVersion: z.literal(1),
  })
  .strict();

export type RealtimeTicket =
  | {
      readonly ok: true;
      readonly ticket: string;
      readonly expiresInSeconds: number;
    }
  | {
      readonly ok: false;
      readonly error:
        | { readonly kind: "session" }
        | { readonly kind: "rate-limited" }
        | { readonly kind: "authority-unavailable" }
        | { readonly kind: "unavailable" }
        | { readonly kind: "invalid-response"; readonly status?: number };
    };

export interface RealtimeTicketRequester {
  requestTicket(signal?: AbortSignal): Promise<RealtimeTicket>;
}

export function createRealtimeTicketRequester(options: {
  readonly authenticatedRequests: AuthenticatedRequestExecutor;
  readonly apiBaseUrl?: string;
}): RealtimeTicketRequester {
  return {
    async requestTicket(signal) {
      if (signal?.aborted) return { ok: false, error: { kind: "unavailable" } };
      const controller = new AbortController();
      const abort = () => controller.abort(signal?.reason);
      signal?.addEventListener("abort", abort, { once: true });
      const timeout = setTimeout(
        () => controller.abort(),
        REALTIME_TICKET_TIMEOUT,
      );
      let response: AuthenticatedRequestResult;
      try {
        response = await options.authenticatedRequests.execute({
          signal: controller.signal,
          createRequest: () => ({
            method: "POST",
            target: publicApiTarget(
              "/api/realtime/tickets",
              options.apiBaseUrl,
            ),
            body: "{}",
            headers: {
              accept: "application/json",
              "content-type": "application/json",
            },
          }),
        });
      } catch {
        return {
          ok: false,
          error: { kind: "unavailable" },
        };
      } finally {
        clearTimeout(timeout);
        signal?.removeEventListener("abort", abort);
      }
      if (
        response.kind === "authentication-failure" ||
        response.kind === "refresh-failure"
      )
        return { ok: false, error: { kind: "session" } };
      if (response.kind === "transport-error") {
        if (response.error.kind === "aborted")
          return { ok: false, error: { kind: "unavailable" } };
        return { ok: false, error: { kind: "unavailable" } };
      }
      const { response: http } = response;
      if (http.status === 401 || http.status === 403)
        return { ok: false, error: { kind: "session" } };
      if (http.status === 429)
        return { ok: false, error: { kind: "rate-limited" } };
      if (http.status === 503)
        return { ok: false, error: { kind: "authority-unavailable" } };
      if (http.status !== 200 && http.status !== 201)
        return {
          ok: false,
          error: { kind: "invalid-response", status: http.status },
        };
      if (http.body.kind !== "json")
        return {
          ok: false,
          error: { kind: "invalid-response", status: http.status },
        };
      const parsed = ticketResponseSchema.safeParse(http.body.value);
      if (!parsed.success)
        return {
          ok: false,
          error: { kind: "invalid-response", status: http.status },
        };
      return {
        ok: true,
        ticket: parsed.data.ticket,
        expiresInSeconds: parsed.data.expiresInSeconds,
      };
    },
  };
}
