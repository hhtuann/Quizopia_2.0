import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import { publicApiTarget } from "../../../lib/api/public-api";
import { monitoringErrorSchema } from "../model/monitoring";

/** Shared failure taxonomy for every Proctoring browser operation. */
export type ProctoringFailure =
  | { readonly kind: "api"; readonly status: number; readonly code: string }
  | {
      readonly kind: "unavailable";
      readonly reason: "network" | "timeout" | "refresh";
    }
  | { readonly kind: "session" }
  | { readonly kind: "aborted" }
  | { readonly kind: "invalid-response"; readonly status?: number }
  | { readonly kind: "invalid-input" };

export type ProctoringResult<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: ProctoringFailure };

export const PROCTORING_REQUEST_TIMEOUT = 15_000;

export const failed = (error: ProctoringFailure): ProctoringResult<never> => ({
  ok: false,
  error,
});

/** True when the server outcome is unknown and an exact replay may be needed. */
export function isUncertainProctoringFailure(
  failure: ProctoringFailure,
): boolean {
  return (
    failure.kind === "aborted" ||
    failure.kind === "unavailable" ||
    failure.kind === "invalid-response" ||
    (failure.kind === "api" && failure.status >= 500)
  );
}

/**
 * Canonical Proctoring API request runner: session-generation fenced,
 * timeout-bounded and replay-safe through the shared authenticated executor.
 * Body serialization happens exactly once per attempt so a 401 retry replays
 * the identical payload (the event contract deduplicates on canonical shape).
 */
export async function runProctoringRequest<T>(options: {
  readonly authenticatedRequests: AuthenticatedRequestExecutor;
  readonly getSessionGeneration: () => number | null;
  readonly boundGeneration: number;
  readonly method: "GET" | "POST";
  readonly path: string;
  readonly body?: unknown;
  readonly schema: {
    safeParse(input: unknown): { success: boolean; data?: T };
  };
  readonly signal?: AbortSignal;
  readonly timeoutMs?: number;
  readonly apiBaseUrl?: string;
}): Promise<ProctoringResult<T>> {
  const {
    authenticatedRequests,
    getSessionGeneration,
    boundGeneration,
    method,
    path,
    body,
    schema,
    signal,
    timeoutMs = PROCTORING_REQUEST_TIMEOUT,
    apiBaseUrl,
  } = options;

  if (signal?.aborted) return failed({ kind: "aborted" });
  if (getSessionGeneration() !== boundGeneration)
    return failed({ kind: "session" });

  const controller = new AbortController();
  let timedOut = false;
  const onExternalAbort = () => controller.abort();
  signal?.addEventListener("abort", onExternalAbort, { once: true });
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const settled = new Promise<ProctoringResult<T>>((resolve) => {
    const listener = () =>
      resolve(
        failed(
          timedOut
            ? { kind: "unavailable", reason: "timeout" }
            : { kind: "aborted" },
        ),
      );
    controller.signal.addEventListener("abort", listener, { once: true });
  });

  const serialized = body === undefined ? undefined : JSON.stringify(body);

  try {
    let result: AuthenticatedRequestResult | ProctoringResult<T>;
    try {
      result = await Promise.race([
        authenticatedRequests.execute({
          signal: controller.signal,
          createRequest: () => {
            if (
              getSessionGeneration() !== boundGeneration ||
              controller.signal.aborted
            ) {
              throw new DOMException(
                "Obsolete proctoring operation",
                "AbortError",
              );
            }
            return {
              method,
              target: publicApiTarget(path, apiBaseUrl),
              body: serialized,
              headers: {
                accept: "application/json",
                ...(serialized === undefined
                  ? {}
                  : { "content-type": "application/json" }),
              },
            };
          },
        }),
        settled,
      ]);
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === "AbortError")
        return failed({ kind: "aborted" });
      return failed({ kind: "unavailable", reason: "network" });
    }

    if ("ok" in result) return result;

    if (result.kind === "authentication-failure")
      return failed({ kind: "session" });
    if (result.kind === "refresh-failure")
      return failed({ kind: "unavailable", reason: "refresh" });
    if (result.kind === "transport-error")
      return failed(
        result.error.kind === "aborted"
          ? { kind: "aborted" }
          : result.error.kind === "invalid-response"
            ? { kind: "invalid-response", status: result.error.status }
            : { kind: "unavailable", reason: "network" },
      );

    const http = result.response;
    if (!http.ok) {
      const parsed =
        http.body.kind === "json"
          ? monitoringErrorSchema.safeParse(http.body.value)
          : null;
      return parsed?.success
        ? failed({
            kind: "api",
            status: http.status,
            code: (parsed.data as { code: string }).code,
          })
        : failed({ kind: "invalid-response", status: http.status });
    }

    if (http.body.kind !== "json")
      return failed({ kind: "invalid-response", status: http.status });

    const validated = schema.safeParse(http.body.value);
    return validated.success
      ? { ok: true, value: validated.data as T }
      : failed({ kind: "invalid-response", status: http.status });
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", onExternalAbort);
    controller.abort();
  }
}
