import { z } from "zod";
import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import { publicApiTarget } from "../../../lib/api/public-api";

/**
 * One-time realtime ticket grant through the public edge. The exact empty JSON object is
 * the whole request; the ticket is returned in memory only and never persisted or logged.
 * Ticket issuance can be pinned to an exact access token, producing a provably bound
 * ticket/JWT pair for the WebSocket AUTH frame even under concurrent token rotation.
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
        | { readonly kind: "unauthorized" }
        | { readonly kind: "rate-limited" }
        | { readonly kind: "authority-unavailable" }
        | { readonly kind: "unavailable" }
        | { readonly kind: "invalid-response"; readonly status?: number };
    };

/** A ticket plus the exact JWT it was issued under; both travel in memory only. */
export type BoundRealtimeTicket =
  | {
      readonly ok: true;
      readonly ticket: string;
      readonly accessToken: string;
      readonly expiresInSeconds: number;
    }
  | {
      readonly ok: false;
      readonly error: Extract<RealtimeTicket, { ok: false }>["error"];
    };

export interface RealtimeTicketRequester {
  requestTicket(signal?: AbortSignal): Promise<RealtimeTicket>;
  /** Issues a ticket pinned to the exact supplied token; no refresh or vault re-read. */
  requestTicketWith(
    accessToken: string,
    signal?: AbortSignal,
  ): Promise<RealtimeTicket>;
  /**
   * Bound ticket credentials for the AUTH frame. Pins the current token so the returned
   * pair stays matched under any concurrent vault rotation; on a stale pinned token it
   * restores freshness through the full refresh-aware executor once, then re-pins. The
   * bound pair is authoritative for the AUTH frame: the token is never re-read after
   * issuance.
   */
  requestBoundTicket(input: {
    getAccessToken: () => string | null;
    signal?: AbortSignal;
  }): Promise<BoundRealtimeTicket>;
}

export function createRealtimeTicketRequester(options: {
  readonly authenticatedRequests: AuthenticatedRequestExecutor;
  readonly apiBaseUrl?: string;
}): RealtimeTicketRequester {
  const executor = options.authenticatedRequests;

  async function perform(
    signal: AbortSignal | undefined,
    run: (
      request: Parameters<AuthenticatedRequestExecutor["executeOnce"]>[0],
    ) => Promise<AuthenticatedRequestResult>,
  ): Promise<RealtimeTicket> {
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
      response = await run({
        signal: controller.signal,
        createRequest: () => ({
          method: "POST",
          target: publicApiTarget("/api/realtime/tickets", options.apiBaseUrl),
          body: "{}",
          headers: {
            accept: "application/json",
            "content-type": "application/json",
          },
        }),
      });
    } catch {
      return { ok: false, error: { kind: "unavailable" } };
    } finally {
      clearTimeout(timeout);
      signal?.removeEventListener("abort", abort);
    }
    if (
      response.kind === "authentication-failure" ||
      response.kind === "refresh-failure"
    )
      return { ok: false, error: { kind: "session" } };
    if (response.kind === "transport-error")
      return { ok: false, error: { kind: "unavailable" } };
    const { response: http } = response;
    if (http.status === 401)
      return { ok: false, error: { kind: "unauthorized" } };
    if (http.status === 403) return { ok: false, error: { kind: "session" } };
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
  }

  return {
    async requestTicket(signal) {
      const result = await perform(signal, (request) =>
        executor.execute(request),
      );
      // The unpinned path keeps its historical 401 classification; only the bound flow
      // distinguishes unauthorized internally to drive its refresh fallback.
      if (!result.ok && result.error.kind === "unauthorized")
        return { ok: false as const, error: { kind: "session" as const } };
      return result;
    },
    requestTicketWith(accessToken, signal) {
      return perform(signal, (request) =>
        executor.executeOnceWithAccessToken(request, accessToken),
      );
    },
    async requestBoundTicket({ getAccessToken, signal }) {
      const pinned = getAccessToken();
      if (pinned === null) return { ok: false, error: { kind: "session" } };
      const first = await perform(signal, (request) =>
        executor.executeOnceWithAccessToken(request, pinned),
      );
      if (first.ok)
        return {
          ok: true,
          ticket: first.ticket,
          accessToken: pinned,
          expiresInSeconds: first.expiresInSeconds,
        };
      // Only a stale pinned token justifies the refresh-aware fallback; any other
      // failure is returned unchanged (the ticket from the fallback is discarded).
      if (first.error.kind !== "unauthorized")
        return { ok: false, error: first.error };
      const restored = await perform(signal, (request) =>
        executor.execute(request),
      );
      if (!restored.ok) {
        return {
          ok: false,
          error:
            restored.error.kind === "unauthorized"
              ? { kind: "session" }
              : restored.error,
        };
      }
      const current = getAccessToken();
      if (current === null) return { ok: false, error: { kind: "session" } };
      const second = await perform(signal, (request) =>
        executor.executeOnceWithAccessToken(request, current),
      );
      if (second.ok)
        return {
          ok: true,
          ticket: second.ticket,
          accessToken: current,
          expiresInSeconds: second.expiresInSeconds,
        };
      return {
        ok: false,
        error:
          second.error.kind === "unauthorized"
            ? { kind: "session" }
            : second.error,
      };
    },
  };
}
