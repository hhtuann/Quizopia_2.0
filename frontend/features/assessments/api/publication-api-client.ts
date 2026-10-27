import { z } from "zod";
import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import { publicApiTarget } from "../../../lib/api/public-api";
import {
  canonicalUuid,
  configurationSchema,
  ownerViewSchema,
  closedPublicationSchema,
  revisionSchema,
  instantSchema,
  durationSchema,
} from "../model/publication";
import type {
  Configuration,
  OwnerView,
  Update,
  Open,
  Schedule,
  ClosedPublication,
  TeacherCommand,
  PublicationSummaryPage,
} from "./publication-contract.generated";

export const PUBLICATION_REQUEST_TIMEOUT = 15_000;
export type PublicationFailure =
  | { readonly kind: "api"; readonly status: number; readonly code: string }
  | {
      readonly kind: "unavailable";
      readonly reason: "network" | "timeout" | "refresh";
    }
  | { readonly kind: "session" }
  | { readonly kind: "aborted" }
  | { readonly kind: "invalid-response"; readonly status?: number }
  | { readonly kind: "invalid-input" };
export type PublicationResult<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: PublicationFailure };

export interface PublicationApiClient {
  list(
    query?: PublicationDirectoryQuery,
    signal?: AbortSignal,
  ): Promise<PublicationResult<PublicationSummaryPage>>;
  create(
    input: Configuration,
    key: string,
    signal?: AbortSignal,
  ): Promise<PublicationResult<OwnerView>>;
  get(id: string, signal?: AbortSignal): Promise<PublicationResult<OwnerView>>;
  update(
    id: string,
    input: Update,
    signal?: AbortSignal,
  ): Promise<PublicationResult<OwnerView>>;
  open(
    id: string,
    input: Open,
    signal?: AbortSignal,
  ): Promise<PublicationResult<OwnerView>>;
  extend(
    id: string,
    input: Schedule,
    signal?: AbortSignal,
  ): Promise<PublicationResult<OwnerView>>;
  close(
    id: string,
    input: TeacherCommand,
    key: string,
    signal?: AbortSignal,
  ): Promise<PublicationResult<ClosedPublication>>;
}

export interface PublicationDirectoryQuery {
  readonly state?: "DRAFT" | "OPEN" | "CLOSED";
  readonly search?: string;
  readonly limit?: number;
  readonly cursor?: string;
}

const summarySchema = z
  .object({
    publicationId: canonicalUuid,
    title: z.string().min(1).max(300),
    audience: z.enum(["CLASS", "PUBLIC"]),
    state: z.enum(["DRAFT", "OPEN", "CLOSED"]),
    startAt: instantSchema,
    endAt: instantSchema,
    createdAt: instantSchema,
    revision: revisionSchema,
    quizId: canonicalUuid,
    quizVersionId: canonicalUuid,
    versionNumber: z.number().int().min(1).max(999999999),
  })
  .strict();
const summaryPageSchema = z
  .object({
    items: z.array(summarySchema).max(100),
    nextCursor: z.string().min(1).max(1024).nullable(),
  })
  .strict();
const directoryQuerySchema = z
  .object({
    state: z.enum(["DRAFT", "OPEN", "CLOSED"]).optional(),
    search: z.string().max(300).optional(),
    limit: z.number().int().min(1).max(100).optional(),
    cursor: z.string().min(1).max(1024).optional(),
  })
  .strict();

const keySchema = z.string().regex(/^[A-Za-z0-9_-]{16,128}$/);
const commandSchema = z.object({ expectedRevision: revisionSchema }).strict();
const updateSchema = commandSchema
  .extend({ configuration: configurationSchema })
  .strict();
const scheduleSchema = commandSchema
  .extend({ endAt: instantSchema, durationMinutes: durationSchema })
  .strict();
const errorSchema = z
  .object({ code: z.string().regex(/^[A-Z_]{1,64}$/) })
  .strict();
const failed = (error: PublicationFailure): PublicationResult<never> => ({
  ok: false,
  error,
});

export function isUncertain(failure: PublicationFailure): boolean {
  return (
    failure.kind === "aborted" ||
    failure.kind === "unavailable" ||
    failure.kind === "invalid-response" ||
    (failure.kind === "api" && failure.status >= 500)
  );
}

export function publicationFailureMessage(error: PublicationFailure): string {
  if (error.kind === "session")
    return "Your session changed or could not be refreshed. Please sign in again.";
  if (error.kind === "aborted")
    return "The request was cancelled and may still have gone through.";
  if (error.kind === "unavailable")
    return "The connection is temporarily unavailable. You are still signed in.";
  if (error.kind === "invalid-input")
    return "Check the configuration and revision before sending this request.";
  if (error.kind === "invalid-response")
    return "The response could not be verified. Refresh before retrying a change.";
  const messages: Record<string, string> = {
    ACTIVE_ATTEMPTS:
      "Active attempts prevent closing. Refresh after learners finish; there is no force-close.",
    CLOSURE_PENDING:
      "A closure request is pending. Schedule changes are blocked; manage it on its own page.",
    REVISION_CONFLICT:
      "This Publication changed. Refresh and review the current revision before trying again.",
    PUBLICATION_CONFLICT:
      "This Publication changed. Refresh and review the current revision before trying again.",
    IDEMPOTENCY_CONFLICT:
      "This request conflicts with an earlier one. Do not repeat it with different settings.",
    CAPACITY_EXCEEDED:
      "Too many requests. Wait a moment, then retry the same action.",
    CLASSROOM_NOT_ACCESSIBLE:
      "This classroom is unavailable or not yours to use. Choose another classroom or retry.",
    SOURCE_NOT_ACCESSIBLE:
      "The selected quiz version is unavailable or no longer accessible to you.",
    PUBLICATION_NOT_FOUND:
      "This Publication is unavailable or you do not have permission to view it.",
    ACCESS_DENIED: "You need the Teacher role and ownership for this action.",
    INVALID_REQUEST:
      "The configuration was rejected. Check the fields and try again.",
  };
  if (error.status === 503)
    return "Part of Quizopia is temporarily unavailable. You are still signed in.";
  if (error.status === 401)
    return "Your session has ended. Please sign in again.";
  return (
    messages[error.code] ??
    `Quizopia could not complete the operation (HTTP ${error.status}). Refresh before retrying.`
  );
}

export function createPublicationApiClient(options: {
  readonly authenticatedRequests: AuthenticatedRequestExecutor;
  readonly getSessionGeneration: () => number | null;
  readonly apiBaseUrl?: string;
}): PublicationApiClient {
  const boundGeneration = options.getSessionGeneration();
  async function execute<T>(
    method: string,
    suffix: string,
    schema: z.ZodType<T>,
    body?: unknown,
    key?: string,
    signal?: AbortSignal,
  ): Promise<PublicationResult<T>> {
    const generation = options.getSessionGeneration();
    if (generation === null || generation !== boundGeneration)
      return failed({ kind: "session" });
    if (signal?.aborted) return failed({ kind: "aborted" });
    const controller = new AbortController();
    let timedOut = false;
    const abort = () => controller.abort();
    signal?.addEventListener("abort", abort, { once: true });
    const timeout = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, PUBLICATION_REQUEST_TIMEOUT);
    let removeAbort = () => {};
    const aborted = new Promise<PublicationResult<T>>((resolve) => {
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
    // Serialize exactly once so the existing executor's one 401 replay is identical.
    const serialized = body === undefined ? undefined : JSON.stringify(body);
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
                  "Obsolete Publication operation",
                  "AbortError",
                );
              return {
                method,
                target: publicApiTarget(
                  `/api/assessments/publications${suffix}`,
                  options.apiBaseUrl,
                ),
                body: serialized,
                headers: {
                  accept: "application/json",
                  ...(serialized === undefined
                    ? {}
                    : { "content-type": "application/json" }),
                  ...(key === undefined ? {} : { "Idempotency-Key": key }),
                },
              };
            },
          })
          .then(
            (response: AuthenticatedRequestResult): PublicationResult<T> => {
              if (response.kind === "authentication-failure")
                return failed({ kind: "session" });
              if (response.kind === "refresh-failure")
                return failed({ kind: "unavailable", reason: "refresh" });
              if (response.kind === "transport-error")
                return failed(
                  response.error.kind === "aborted"
                    ? { kind: "aborted" }
                    : response.error.kind === "invalid-response"
                      ? { kind: "invalid-response" }
                      : { kind: "unavailable", reason: "network" },
                );
              const http = response.response;
              if (!http.ok) {
                const parsed =
                  http.body.kind === "json"
                    ? errorSchema.safeParse(http.body.value)
                    : null;
                return parsed?.success
                  ? failed({
                      kind: "api",
                      status: http.status,
                      code: parsed.data.code,
                    })
                  : failed({ kind: "invalid-response", status: http.status });
              }
              const expectedStatus =
                method === "POST" && suffix === "" ? 201 : 200;
              if (http.status !== expectedStatus || http.body.kind !== "json")
                return failed({
                  kind: "invalid-response",
                  status: http.status,
                });
              const parsed = schema.safeParse(http.body.value);
              return parsed.success
                ? { ok: true, value: parsed.data }
                : failed({ kind: "invalid-response", status: http.status });
            },
          )
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
      signal?.removeEventListener("abort", abort);
      removeAbort();
    }
  }

  function owner(
    method: string,
    id: string,
    suffix: string,
    body: unknown,
    signal?: AbortSignal,
  ) {
    if (!canonicalUuid.safeParse(id).success)
      return Promise.resolve(failed({ kind: "invalid-input" }));
    return execute(
      method,
      `/${id}${suffix}`,
      ownerViewSchema,
      body,
      undefined,
      signal,
    ).then((result) =>
      result.ok && result.value.publicationId !== id
        ? failed({ kind: "invalid-response" })
        : result,
    );
  }

  return Object.freeze({
    list(query = {}, signal) {
      if (!directoryQuerySchema.safeParse(query).success)
        return Promise.resolve(failed({ kind: "invalid-input" }));
      const params = new URLSearchParams({ limit: String(query.limit ?? 20) });
      if (query.state) params.set("state", query.state);
      if (query.search?.trim()) params.set("search", query.search.trim());
      if (query.cursor) params.set("cursor", query.cursor);
      return execute(
        "GET",
        `?${params}`,
        summaryPageSchema,
        undefined,
        undefined,
        signal,
      );
    },
    create(input, key, signal) {
      if (
        !configurationSchema.safeParse(input).success ||
        !keySchema.safeParse(key).success
      )
        return Promise.resolve(failed({ kind: "invalid-input" }));
      return execute("POST", "", ownerViewSchema, input, key, signal);
    },
    get(id, signal) {
      return owner("GET", id, "", undefined, signal);
    },
    update(id, input, signal) {
      return updateSchema.safeParse(input).success
        ? owner("PUT", id, "", input, signal)
        : Promise.resolve(failed({ kind: "invalid-input" }));
    },
    open(id, input, signal) {
      return commandSchema.safeParse(input).success
        ? owner("POST", id, "/open", input, signal)
        : Promise.resolve(failed({ kind: "invalid-input" }));
    },
    extend(id, input, signal) {
      return scheduleSchema.safeParse(input).success
        ? owner("PATCH", id, "/schedule", input, signal)
        : Promise.resolve(failed({ kind: "invalid-input" }));
    },
    close(id, input, key, signal) {
      if (
        !canonicalUuid.safeParse(id).success ||
        !commandSchema.safeParse(input).success ||
        !keySchema.safeParse(key).success
      )
        return Promise.resolve(failed({ kind: "invalid-input" }));
      return execute(
        "POST",
        `/${id}/close`,
        closedPublicationSchema,
        input,
        key,
        signal,
      ).then((result) =>
        result.ok && result.value.publicationId !== id
          ? failed({ kind: "invalid-response" })
          : result,
      );
    },
  } satisfies PublicationApiClient);
}
