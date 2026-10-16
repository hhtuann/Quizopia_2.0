import { z } from "zod";
import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import { publicApiTarget } from "../../../lib/api/public-api";

/**
 * Shared request core for the W3-B3 Assessment features (closure workflow,
 * Student notification inbox and Teacher results). It preserves the canonical
 * LD-05 execution rules used by the W3-A Publication client: memory-only USER
 * bearer, session-generation fencing, one 401 replay performed by the shared
 * executor, request abortion and stale-response fencing. A 503 never triggers
 * a feature-level refresh/logout cascade.
 */
export const ASSESSMENT_REQUEST_TIMEOUT = 15_000;

export type AssessmentFailure =
  | { readonly kind: "api"; readonly status: number; readonly code: string }
  | {
      readonly kind: "unavailable";
      readonly reason: "network" | "timeout" | "refresh";
    }
  | { readonly kind: "session" }
  | { readonly kind: "aborted" }
  | { readonly kind: "invalid-response"; readonly status?: number }
  | { readonly kind: "invalid-input" };

export type AssessmentResult<T> =
  | { readonly ok: true; readonly value: T; readonly status: number }
  | { readonly ok: false; readonly error: AssessmentFailure };

export interface PageQuery {
  readonly limit?: number;
  readonly cursor?: string;
}

export const idempotencyKeySchema = z.string().regex(/^[A-Za-z0-9_-]{16,128}$/);
export const pageLimitSchema = z.number().int().min(1).max(100);
export const opaqueCursorSchema = z.string().min(1).max(1024);

const errorEnvelopeSchema = z
  .object({ code: z.string().regex(/^[A-Z_]{1,64}$/) })
  .strict();

const failed = (error: AssessmentFailure): AssessmentResult<never> => ({
  ok: false,
  error,
});

export function isUncertainAssessmentFailure(
  failure: AssessmentFailure,
): boolean {
  return (
    failure.kind === "aborted" ||
    failure.kind === "unavailable" ||
    failure.kind === "invalid-response" ||
    (failure.kind === "api" && failure.status >= 500)
  );
}

const codeMessages: Record<string, string> = {
  ACTIVE_ATTEMPTS:
    "Active Attempts prevent direct closure. Manage the delayed closure workflow or refresh after learners finish; force-close is not available.",
  CLOSURE_PENDING:
    "A closure workflow is already live for this Publication. Manage that request instead of creating another.",
  REVISION_CONFLICT:
    "This Publication changed on the server. Refresh authoritative state and review the current revision before trying again.",
  PUBLICATION_CONFLICT:
    "The Publication state or revision changed. Refresh authoritative details and review your action.",
  IDEMPOTENCY_CONFLICT:
    "This operation identity conflicts with an earlier request. Do not repeat it with different settings; refresh state and start a new deliberate action.",
  WINDOW_NOT_ENDED:
    "The server's authoritative clock has not reached the participation end. Delayed closure cannot be requested yet; browser time is never used.",
  CLOSURE_NOT_READY:
    "The server still waits on confirmed notices or 48-hour cutoffs. Already committed progress remains; refresh the request status.",
  CLOSURE_FINALIZATION_STARTED:
    "Closure finalization has durably started, so this request can no longer be cancelled.",
  CLOSURE_RESOLVED:
    "This closure request is already resolved. Start a new deliberate action if one is still required.",
  PUBLICATION_NOT_FOUND:
    "This Publication is unavailable or you do not have permission to view it.",
  CLOSURE_REQUEST_NOT_FOUND:
    "This closure request is unavailable or you do not have permission to view it.",
  NOTIFICATION_NOT_FOUND:
    "This notification is unavailable or does not belong to the current account.",
  ACCESS_DENIED:
    "The server denied this operation. Current role authority and ownership are required.",
  INVALID_REQUEST:
    "The server rejected the request as malformed. No mutation occurred.",
  CAPACITY_EXCEEDED:
    "The server is temporarily throttling this operation. Retry the same operation key after a short wait.",
};

export function assessmentFailureMessage(failure: AssessmentFailure): string {
  if (failure.kind === "session")
    return "Your session changed or could not be refreshed. Sign in or verify your account before continuing.";
  if (failure.kind === "aborted")
    return "The request was cancelled. A mutation may still have reached the server.";
  if (failure.kind === "unavailable")
    return "Verification or the connection is temporarily unavailable. Your account has not been logged out.";
  if (failure.kind === "invalid-input")
    return "Check the identifier, revision and query before sending this request.";
  if (failure.kind === "invalid-response")
    return "The server response could not be verified. Refresh authoritative state before retrying a change.";
  if (failure.status === 503)
    return "Required authority, dependency, persistence or grading is unavailable. Your account has not been logged out.";
  if (failure.status === 401)
    return "Your session could not authorize this request. Sign in again before continuing.";
  if (failure.status === 403)
    return "The server denied this operation for the current account or role.";
  if (failure.status === 404)
    return "The resource is unavailable or not accessible for the current account.";
  if (failure.status === 429) return codeMessages.CAPACITY_EXCEEDED;
  return (
    codeMessages[failure.code] ??
    `The server could not complete the operation (HTTP ${failure.status}). Refresh status before retrying.`
  );
}

export interface AssessmentRequestInput<T> {
  /** Full method/path under /api/assessments, for example `/publications/{id}/close`. */
  readonly method: "GET" | "POST";
  readonly path: string;
  readonly schema: z.ZodType<T>;
  readonly body?: unknown;
  readonly key?: string;
  readonly query?: PageQuery;
  readonly signal?: AbortSignal;
  /** Extra success statuses beyond 200 (for example 201 creation and 202 accepted progress). */
  readonly successStatuses?: readonly number[];
  readonly timeoutMs?: number;
}

export interface AssessmentRequester {
  execute<T>(input: AssessmentRequestInput<T>): Promise<AssessmentResult<T>>;
}

export function createAssessmentRequester(options: {
  readonly authenticatedRequests: AuthenticatedRequestExecutor;
  readonly getSessionGeneration: () => number | null;
  readonly apiBaseUrl?: string;
}): AssessmentRequester {
  const boundGeneration = options.getSessionGeneration();

  function buildQuery(query: PageQuery | undefined): string {
    if (query === undefined) return "";
    const parameters = new URLSearchParams();
    if (query.limit !== undefined) {
      if (!pageLimitSchema.safeParse(query.limit).success)
        throw new TypeError("Invalid page limit.");
      parameters.set("limit", String(query.limit));
    }
    if (query.cursor !== undefined) {
      if (!opaqueCursorSchema.safeParse(query.cursor).success)
        throw new TypeError("Invalid opaque cursor.");
      // The cursor is opaque: pass it through without decoding or alteration.
      parameters.set("cursor", query.cursor);
    }
    const encoded = parameters.toString();
    return encoded.length === 0 ? "" : `?${encoded}`;
  }

  async function execute<T>(
    input: AssessmentRequestInput<T>,
  ): Promise<AssessmentResult<T>> {
    const generation = options.getSessionGeneration();
    if (generation === null || generation !== boundGeneration)
      return failed({ kind: "session" });
    if (input.signal?.aborted) return failed({ kind: "aborted" });
    if (input.method === "GET" && input.body !== undefined)
      return failed({ kind: "invalid-input" });

    let suffix: string;
    try {
      suffix = `${input.path}${buildQuery(input.query)}`;
    } catch {
      return failed({ kind: "invalid-input" });
    }

    const controller = new AbortController();
    let timedOut = false;
    const abort = () => controller.abort();
    input.signal?.addEventListener("abort", abort, { once: true });
    const timeout = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, input.timeoutMs ?? ASSESSMENT_REQUEST_TIMEOUT);
    let removeAbort = () => {};
    const aborted = new Promise<AssessmentResult<T>>((resolve) => {
      const listener = () =>
        resolve(
          failed(
            timedOut
              ? { kind: "unavailable", reason: "timeout" }
              : { kind: "aborted" },
          ),
        );
      controller.signal.addEventListener("abort", listener, { once: true });
      removeAbort = () =>
        controller.signal.removeEventListener("abort", listener);
    });
    // Serialize exactly once so the executor's single 401 replay reuses identical bytes.
    const serialized =
      input.body === undefined ? undefined : JSON.stringify(input.body);
    try {
      const result = await Promise.race([
        options.authenticatedRequests
          .execute({
            signal: controller.signal,
            createRequest: () => {
              if (
                options.getSessionGeneration() !== generation ||
                controller.signal.aborted
              )
                throw new DOMException(
                  "Obsolete Assessment operation",
                  "AbortError",
                );
              return {
                method: input.method,
                target: publicApiTarget(
                  `/api/assessments${suffix}`,
                  options.apiBaseUrl,
                ),
                body: serialized,
                headers: {
                  accept: "application/json",
                  ...(serialized === undefined
                    ? {}
                    : { "content-type": "application/json" }),
                  ...(input.key === undefined
                    ? {}
                    : { "Idempotency-Key": input.key }),
                },
              };
            },
          })
          .then((response: AuthenticatedRequestResult): AssessmentResult<T> => {
            if (response.kind === "authentication-failure")
              return failed({ kind: "session" });
            if (response.kind === "refresh-failure")
              return failed({ kind: "unavailable", reason: "refresh" });
            if (response.kind === "transport-error")
              return failed(
                response.error.kind === "aborted"
                  ? { kind: "aborted" }
                  : response.error.kind === "invalid-response"
                    ? {
                        kind: "invalid-response",
                        status: response.error.status,
                      }
                    : { kind: "unavailable", reason: "network" },
              );
            const http = response.response;
            if (!http.ok) {
              const parsed =
                http.body.kind === "json"
                  ? errorEnvelopeSchema.safeParse(http.body.value)
                  : null;
              return parsed?.success
                ? failed({
                    kind: "api",
                    status: http.status,
                    code: parsed.data.code,
                  })
                : failed({
                    kind: "invalid-response",
                    status: http.status,
                  });
            }
            const accepted =
              http.status === 200 ||
              input.successStatuses?.includes(http.status);
            if (!accepted || http.body.kind !== "json")
              return failed({
                kind: "invalid-response",
                status: http.status,
              });
            const parsed = input.schema.safeParse(http.body.value);
            return parsed.success
              ? { ok: true, value: parsed.data, status: http.status }
              : failed({ kind: "invalid-response", status: http.status });
          })
          .catch(() =>
            failed(
              controller.signal.aborted
                ? { kind: "aborted" }
                : { kind: "unavailable", reason: "network" },
            ),
          ),
        aborted,
      ]);
      if (options.getSessionGeneration() !== generation)
        return failed({ kind: "session" });
      if (timedOut) return failed({ kind: "unavailable", reason: "timeout" });
      return controller.signal.aborted ? failed({ kind: "aborted" }) : result;
    } finally {
      clearTimeout(timeout);
      input.signal?.removeEventListener("abort", abort);
      removeAbort();
    }
  }

  return Object.freeze({ execute });
}
