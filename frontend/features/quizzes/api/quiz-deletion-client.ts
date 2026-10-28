import { z } from "zod";
import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import type {
  HttpResponse,
  HttpTransportError,
} from "../../../lib/api/http-transport";
import { publicApiTarget } from "../../../lib/api/public-api";
import { quizDeletionStatusSchema } from "./quiz-api-client";

/**
 * FB-015 owner-only quiz deletion client (ADR-018 tombstone-first saga).
 * Mirrors contracts/quiz-deletion.openapi.yaml: POST accepts an
 * Idempotency-Key (16-128 [A-Za-z0-9_-]) and stays idempotent per key;
 * GET reads one owned operation. Responses are no-store server-side.
 */

const canonicalIdempotencyKeySchema = z
  .string()
  .regex(/^[A-Za-z0-9_-]{16,128}$/);

const deletionAcceptedSchema = z
  .object({
    operationId: z.string().uuid(),
    status: quizDeletionStatusSchema,
  })
  .strict();

const deletionStatusSchema = z
  .object({
    operationId: z.string().uuid(),
    status: z.string(),
    createdAt: z.string(),
    updatedAt: z.string(),
    lastReason: z.string().nullable(),
    stages: z.array(z.string()),
    note: z.string().nullable().optional(),
  })
  .strict();

const deletionErrorSchema = z
  .object({
    code: z.string(),
    message: z.string(),
    status: z.number().int(),
    path: z.string(),
  })
  .strict();

export type QuizDeletionAccepted = z.infer<typeof deletionAcceptedSchema>;
export type QuizDeletionOperationStatus = z.infer<typeof deletionStatusSchema>;

export interface QuizDeletionApiErrorEnvelope {
  readonly code: string;
  readonly message: string;
  readonly path: string;
  readonly status: number;
}

export type QuizDeletionFailure =
  | { readonly kind: "api-error"; readonly error: QuizDeletionApiErrorEnvelope }
  | { readonly kind: "authentication-failure"; readonly reason: string }
  | { readonly kind: "session-changed" }
  | { readonly kind: "transport-error"; readonly error: HttpTransportError }
  | { readonly kind: "invalid-input" }
  | { readonly kind: "unexpected-response"; readonly status: number };

export type QuizDeletionResult<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: QuizDeletionFailure };

export interface QuizDeletionRequestInput {
  /** Exact current quiz title; the backend trims and compares case-sensitively. */
  readonly confirmTitle: string;
  /** Generated once per user intent; reused verbatim across retries. */
  readonly idempotencyKey: string;
}

export interface QuizDeletionApiClient {
  requestDeletion(
    quizId: string,
    input: QuizDeletionRequestInput,
    signal?: AbortSignal,
  ): Promise<QuizDeletionResult<QuizDeletionAccepted>>;
  getDeletionStatus(
    quizId: string,
    operationId: string,
    signal?: AbortSignal,
  ): Promise<QuizDeletionResult<QuizDeletionOperationStatus>>;
}

/**
 * crypto.randomUUID emits canonical hyphenated hex (36 chars), which satisfies
 * the backend's [A-Za-z0-9_-]{16,128} class. The manual fallback keeps the
 * same class on older runtimes; it never leaks anything sensitive.
 */
export function newDeletionIdempotencyKey(): string {
  const randomUUID =
    globalThis.crypto?.randomUUID?.bind(globalThis.crypto) ?? null;
  if (randomUUID !== null) {
    return randomUUID();
  }
  const bytes = new Uint8Array(24);
  globalThis.crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(36).padStart(1, "0"))
    .join("")
    .replace(/[^a-z0-9]/g, "x")
    .padEnd(24, "0")
    .slice(0, 24);
}

export function isDeletionIdempotencyKeyValid(key: string): boolean {
  return canonicalIdempotencyKeySchema.safeParse(key).success;
}

function normalizeAuthenticatedResult(
  result: AuthenticatedRequestResult,
):
  | { readonly ok: true; readonly response: HttpResponse }
  | { readonly ok: false; readonly error: QuizDeletionFailure } {
  if (result.kind === "transport-error") {
    return {
      ok: false,
      error: { kind: "transport-error", error: result.error },
    };
  }
  if (result.kind === "authentication-failure") {
    return {
      ok: false,
      error: { kind: "authentication-failure", reason: result.reason },
    };
  }
  if (result.kind === "refresh-failure") {
    return {
      ok: false,
      error: { kind: "authentication-failure", reason: "refresh-failure" },
    };
  }
  return { ok: true, response: result.response };
}

export function createQuizDeletionApiClient(options: {
  readonly authenticatedRequests: AuthenticatedRequestExecutor;
  readonly getSessionGeneration: () => number | null;
  readonly apiBaseUrl?: string;
}): QuizDeletionApiClient {
  const { authenticatedRequests, getSessionGeneration, apiBaseUrl } = options;
  // Bind to the session that created the client: a sign-out/sign-in between
  // dialog open and confirm must never fire the destructive request under a
  // different session (same guard as publication mutations).
  const boundGeneration = getSessionGeneration();

  function parseFailure(response: HttpResponse): QuizDeletionResult<never> {
    if (response.body.kind === "json") {
      const parsed = deletionErrorSchema.safeParse(response.body.value);
      if (parsed.success && parsed.data.status === response.status) {
        return { ok: false, error: { kind: "api-error", error: parsed.data } };
      }
    }
    return {
      ok: false,
      error: { kind: "unexpected-response", status: response.status },
    };
  }

  function expectJson<T>(
    response: HttpResponse,
    statuses: readonly number[],
    schema: z.ZodType<T>,
  ): QuizDeletionResult<T> {
    if (!response.ok) {
      return parseFailure(response);
    }
    if (!statuses.includes(response.status) || response.body.kind !== "json") {
      return {
        ok: false,
        error: { kind: "unexpected-response", status: response.status },
      };
    }
    const parsed = schema.safeParse(response.body.value);
    return parsed.success
      ? { ok: true, value: parsed.data }
      : {
          ok: false,
          error: { kind: "unexpected-response", status: response.status },
        };
  }

  async function execute<T>(
    path: string,
    method: "GET" | "POST",
    body: { readonly confirmTitle: string } | undefined,
    idempotencyKey: string | undefined,
    schema: z.ZodType<T>,
    successStatuses: readonly number[],
    signal?: AbortSignal,
  ): Promise<QuizDeletionResult<T>> {
    if (
      getSessionGeneration() === null ||
      getSessionGeneration() !== boundGeneration
    ) {
      return { ok: false, error: { kind: "session-changed" } };
    }
    if (
      idempotencyKey !== undefined &&
      !isDeletionIdempotencyKeyValid(idempotencyKey)
    ) {
      return { ok: false, error: { kind: "invalid-input" } };
    }
    let normalized;
    try {
      normalized = normalizeAuthenticatedResult(
        await authenticatedRequests.execute({
          signal,
          createRequest: () => {
            if (getSessionGeneration() !== boundGeneration) {
              throw new DOMException(
                "Obsolete quiz deletion operation",
                "AbortError",
              );
            }
            return {
              method,
              target: publicApiTarget(path, apiBaseUrl),
              body: body === undefined ? undefined : JSON.stringify(body),
              headers: new Headers({
                accept: "application/json",
                ...(body === undefined
                  ? {}
                  : { "content-type": "application/json" }),
                ...(idempotencyKey === undefined
                  ? {}
                  : { "Idempotency-Key": idempotencyKey }),
              }),
            };
          },
        }),
      );
    } catch {
      return { ok: false, error: { kind: "session-changed" } };
    }
    if (!normalized.ok) {
      return normalized;
    }
    if (getSessionGeneration() !== boundGeneration) {
      return { ok: false, error: { kind: "session-changed" } };
    }
    return expectJson(normalized.response, successStatuses, schema);
  }

  function requireUuid(value: string): boolean {
    return z.string().uuid().safeParse(value).success;
  }

  const client: QuizDeletionApiClient = {
    async requestDeletion(quizId, input, signal) {
      if (!requireUuid(quizId)) {
        return { ok: false, error: { kind: "invalid-input" } };
      }
      return execute(
        `/api/quizzes/${encodeURIComponent(quizId)}/deletion-requests`,
        "POST",
        { confirmTitle: input.confirmTitle },
        input.idempotencyKey,
        deletionAcceptedSchema,
        [202],
        signal,
      );
    },
    async getDeletionStatus(quizId, operationId, signal) {
      if (!requireUuid(quizId) || !requireUuid(operationId)) {
        return { ok: false, error: { kind: "invalid-input" } };
      }
      const result = await execute(
        `/api/quizzes/${encodeURIComponent(quizId)}/deletion-requests/${encodeURIComponent(operationId)}`,
        "GET",
        undefined,
        undefined,
        deletionStatusSchema,
        [200],
        signal,
      );
      // Normalize an absent note key to null for one consistent shape.
      return result.ok
        ? {
            ...result,
            value: { ...result.value, note: result.value.note ?? null },
          }
        : result;
    },
  };
  return Object.freeze(client);
}
