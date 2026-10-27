import { z } from "zod";
import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import { publicApiTarget } from "../../../lib/api/public-api";
import {
  translateMessage,
  type TranslationValues,
} from "../../../lib/i18n/core";
import type {
  AbandonAck,
  AbandonRequest,
  Aggregate,
  Answer,
  AttemptView,
  Entry,
  Result,
  Review,
  SaveAck,
  SaveRequest,
  StartRequest,
  StudentView,
  SubmitRequest,
} from "./attempt-contract.generated";
import {
  abandonAckSchema,
  abandonRequestSchema,
  aggregateSchema,
  attemptViewSchema,
  canonicalUuid,
  entrySchema,
  reviewSchema,
  saveAckSchema,
  saveRequestSchema,
  startRequestSchema,
  studentViewSchema,
  submitRequestSchema,
  resultSchema,
} from "../model/attempt";

export const ATTEMPT_REQUEST_TIMEOUT = 15_000;

const keySchema = z.string().regex(/^[A-Za-z0-9_-]{16,128}$/);
const questionNumberSchema = z.number().int().min(1).max(2_147_483_647);
const errorSchema = z
  .object({
    code: z.string().regex(/^[A-Z_]{1,64}$/),
  })
  .strict();

export type AttemptFailure =
  | { readonly kind: "api"; readonly status: number; readonly code: string }
  | {
      readonly kind: "unavailable";
      readonly reason: "network" | "timeout" | "refresh";
    }
  | { readonly kind: "session" }
  | { readonly kind: "aborted" }
  | { readonly kind: "invalid-response"; readonly status?: number }
  | { readonly kind: "invalid-input" };

export type AttemptResult<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: AttemptFailure };

export interface AttemptApiClient {
  enterPublic(
    input: Entry,
    signal?: AbortSignal,
  ): Promise<AttemptResult<StudentView>>;
  enterClass(
    publicationId: string,
    signal?: AbortSignal,
  ): Promise<AttemptResult<StudentView>>;
  start(
    publicationId: string,
    input: StartRequest,
    key: string,
    signal?: AbortSignal,
  ): Promise<AttemptResult<AttemptView>>;
  retake(
    publicationId: string,
    input: StartRequest,
    key: string,
    signal?: AbortSignal,
  ): Promise<AttemptResult<AttemptView>>;
  getAttempt(
    attemptId: string,
    signal?: AbortSignal,
  ): Promise<AttemptResult<AttemptView>>;
  save(
    attemptId: string,
    questionNumber: number,
    input: SaveRequest,
    key: string,
    signal?: AbortSignal,
  ): Promise<AttemptResult<SaveAck>>;
  submit(
    attemptId: string,
    input: SubmitRequest,
    key: string,
    signal?: AbortSignal,
  ): Promise<AttemptResult<Result>>;
  abandon(
    attemptId: string,
    input: AbandonRequest,
    key: string,
    signal?: AbortSignal,
  ): Promise<AttemptResult<AbandonAck>>;
  getResult(
    attemptId: string,
    signal?: AbortSignal,
  ): Promise<AttemptResult<Result>>;
  getAggregate(
    publicationId: string,
    signal?: AbortSignal,
  ): Promise<AttemptResult<Aggregate>>;
  getReview(
    attemptId: string,
    signal?: AbortSignal,
  ): Promise<AttemptResult<Review>>;
}

const failed = (error: AttemptFailure): AttemptResult<never> => ({
  ok: false,
  error,
});

export function isUncertainAttemptMutation(error: AttemptFailure): boolean {
  return (
    error.kind === "aborted" ||
    error.kind === "unavailable" ||
    error.kind === "invalid-response" ||
    (error.kind === "api" && error.status >= 500)
  );
}

/** Presentation-only failure copy. The localize callback resolves catalog
 * keys; deterministic English is the fallback for unknown messages. */
export function attemptFailureMessage(
  error: AttemptFailure,
  localize: (message: string, values?: TranslationValues) => string = (
    message,
    values,
  ) => translateMessage("en", message, values),
): string {
  if (error.kind === "session")
    return localize(
      "Your session changed or ended. Sign in again before continuing.",
    );
  if (error.kind === "aborted")
    return localize("The request was cancelled. Its outcome may be uncertain.");
  if (error.kind === "unavailable")
    return localize(
      "Quizopia is temporarily unavailable. You are still signed in.",
    );
  if (error.kind === "invalid-input")
    return localize(
      "The request could not be sent because its input is invalid.",
    );
  if (error.kind === "invalid-response")
    return localize(
      "The response could not be verified. Refresh before continuing.",
    );
  const messages: Record<string, string> = {
    ACCESS_DENIED: "Student access is required for this assessment.",
    PUBLICATION_NOT_FOUND:
      "This assessment is unavailable or you do not have permission to access it.",
    ATTEMPT_NOT_FOUND:
      "This attempt is unavailable or does not belong to the current account.",
    CLASSROOM_NOT_ACCESSIBLE:
      "Your current class membership does not grant access.",
    WINDOW_NOT_STARTED: "This assessment is not available yet.",
    WINDOW_ENDED: "The window for starting this assessment has ended.",
    ATTEMPTS_EXHAUSTED: "No more attempts are available for this assessment.",
    ACTIVE_ATTEMPT_EXISTS:
      "An active attempt already exists. Resume it instead.",
    RETAKE_NOT_AVAILABLE: "A retake is not currently available.",
    REVISION_CONFLICT:
      "This attempt changed. Refresh to reconcile your unsaved answer.",
    IDEMPOTENCY_CONFLICT:
      "This request was already used for different content.",
    ATTEMPT_TERMINAL: "This attempt has already reached a terminal state.",
    ATTEMPT_DEADLINE_REACHED: "The attempt deadline has been reached.",
    CLOSURE_CUTOFF_REACHED: "The closure cutoff has been reached.",
    CLOSURE_PENDING:
      "A closure workflow is pending, so a new Attempt cannot be created.",
    RESULT_NOT_AVAILABLE: "A result is not available for this attempt.",
    REVIEW_NOT_AVAILABLE: "Answer review opens after the assessment is closed.",
    CAPACITY_EXCEEDED:
      "Your answers are too large to submit. Nothing was cut or sent.",
  };
  if (error.status === 429)
    return localize("Too many requests. Please wait, then retry.");
  if (error.status === 503)
    return localize(
      "A required part of Quizopia is unavailable. You are still signed in.",
    );
  if (error.status === 401)
    return localize("Your session has ended. Please sign in again.");
  if (messages[error.code] !== undefined)
    return localize(messages[error.code]!);
  return localize("Quizopia could not complete the request (HTTP {status}).", {
    status: error.status,
  });
}

export function createAttemptApiClient(options: {
  readonly authenticatedRequests: AuthenticatedRequestExecutor;
  readonly getSessionGeneration: () => number | null;
  readonly apiBaseUrl?: string;
}): AttemptApiClient {
  const boundGeneration = options.getSessionGeneration();

  async function execute<T>(input: {
    readonly method: "GET" | "POST";
    readonly path: string;
    readonly schema: z.ZodType<T>;
    readonly body?: unknown;
    readonly key?: string;
    readonly signal?: AbortSignal;
    readonly acceptedStatuses?: readonly number[];
  }): Promise<AttemptResult<T>> {
    const generation = options.getSessionGeneration();
    if (generation === null || generation !== boundGeneration)
      return failed({ kind: "session" });
    if (input.signal?.aborted) return failed({ kind: "aborted" });

    const controller = new AbortController();
    let timedOut = false;
    const abort = () => controller.abort(input.signal?.reason);
    input.signal?.addEventListener("abort", abort, { once: true });
    const timeout = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, ATTEMPT_REQUEST_TIMEOUT);
    const serialized =
      input.body === undefined ? undefined : JSON.stringify(input.body);

    try {
      let response: AuthenticatedRequestResult;
      try {
        response = await options.authenticatedRequests.execute({
          signal: controller.signal,
          createRequest: () => {
            if (
              options.getSessionGeneration() !== generation ||
              controller.signal.aborted
            )
              throw new DOMException(
                "Obsolete Attempt operation",
                "AbortError",
              );
            return {
              method: input.method,
              target: publicApiTarget(input.path, options.apiBaseUrl),
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
        });
      } catch {
        return failed(
          controller.signal.aborted
            ? timedOut
              ? { kind: "unavailable", reason: "timeout" }
              : { kind: "aborted" }
            : { kind: "unavailable", reason: "network" },
        );
      }
      if (options.getSessionGeneration() !== generation)
        return failed({ kind: "session" });
      if (timedOut) return failed({ kind: "unavailable", reason: "timeout" });
      if (response.kind === "authentication-failure")
        return failed({ kind: "session" });
      if (response.kind === "refresh-failure")
        return failed({ kind: "unavailable", reason: "refresh" });
      if (response.kind === "transport-error")
        return failed(
          response.error.kind === "aborted"
            ? { kind: "aborted" }
            : response.error.kind === "invalid-response"
              ? { kind: "invalid-response", status: response.error.status }
              : { kind: "unavailable", reason: "network" },
        );
      const http = response.response;
      if (!http.ok) {
        const parsed =
          http.body.kind === "json"
            ? errorSchema.safeParse(http.body.value)
            : null;
        return parsed?.success
          ? failed({ kind: "api", status: http.status, code: parsed.data.code })
          : failed({ kind: "invalid-response", status: http.status });
      }
      if (
        !(input.acceptedStatuses ?? [200]).includes(http.status) ||
        http.body.kind !== "json"
      )
        return failed({ kind: "invalid-response", status: http.status });
      const parsed = input.schema.safeParse(http.body.value);
      return parsed.success
        ? { ok: true, value: parsed.data }
        : failed({ kind: "invalid-response", status: http.status });
    } finally {
      clearTimeout(timeout);
      input.signal?.removeEventListener("abort", abort);
    }
  }

  function validId(id: string): boolean {
    return canonicalUuid.safeParse(id).success;
  }

  function creation(
    action: "start" | "retake",
    publicationId: string,
    input: StartRequest,
    key: string,
    signal?: AbortSignal,
  ) {
    if (
      !validId(publicationId) ||
      !startRequestSchema.safeParse(input).success ||
      !keySchema.safeParse(key).success
    )
      return Promise.resolve(failed({ kind: "invalid-input" }));
    return execute({
      method: "POST",
      path: `/api/assessments/publications/${publicationId}/attempts/${action}`,
      body: input,
      key,
      schema: attemptViewSchema,
      acceptedStatuses: [200, 201],
      signal,
    }).then((result) =>
      result.ok && result.value.publicationId !== publicationId
        ? failed({ kind: "invalid-response" })
        : result,
    );
  }

  return Object.freeze({
    enterPublic(input, signal) {
      if (!entrySchema.safeParse(input).success)
        return Promise.resolve(failed({ kind: "invalid-input" }));
      return execute({
        method: "POST",
        path: "/api/assessments/publications/entry",
        body: input,
        schema: studentViewSchema,
        signal,
      });
    },
    enterClass(publicationId, signal) {
      if (!validId(publicationId))
        return Promise.resolve(failed({ kind: "invalid-input" }));
      return execute({
        method: "POST",
        path: `/api/assessments/publications/${publicationId}/entry`,
        schema: studentViewSchema,
        signal,
      }).then((result) =>
        result.ok && result.value.publicationId !== publicationId
          ? failed({ kind: "invalid-response" })
          : result,
      );
    },
    start(publicationId, input, key, signal) {
      return creation("start", publicationId, input, key, signal);
    },
    retake(publicationId, input, key, signal) {
      return creation("retake", publicationId, input, key, signal);
    },
    getAttempt(attemptId, signal) {
      if (!validId(attemptId))
        return Promise.resolve(failed({ kind: "invalid-input" }));
      return execute({
        method: "GET",
        path: `/api/assessments/attempts/${attemptId}`,
        schema: attemptViewSchema,
        signal,
      }).then((result) =>
        result.ok && result.value.attemptId !== attemptId
          ? failed({ kind: "invalid-response" })
          : result,
      );
    },
    save(attemptId, questionNumber, input, key, signal) {
      if (
        !validId(attemptId) ||
        !questionNumberSchema.safeParse(questionNumber).success ||
        !saveRequestSchema.safeParse(input).success ||
        !keySchema.safeParse(key).success
      )
        return Promise.resolve(failed({ kind: "invalid-input" }));
      return execute({
        method: "POST",
        path: `/api/assessments/attempts/${attemptId}/questions/${questionNumber}/answer`,
        body: input,
        key,
        schema: saveAckSchema,
        signal,
      }).then((result) =>
        result.ok &&
        (result.value.attemptId !== attemptId ||
          result.value.questionNumber !== questionNumber)
          ? failed({ kind: "invalid-response" })
          : result,
      );
    },
    submit(attemptId, input, key, signal) {
      if (
        !validId(attemptId) ||
        !submitRequestSchema.safeParse(input).success ||
        !keySchema.safeParse(key).success
      )
        return Promise.resolve(failed({ kind: "invalid-input" }));
      return execute({
        method: "POST",
        path: `/api/assessments/attempts/${attemptId}/submit`,
        body: input,
        key,
        schema: resultSchema,
        signal,
      }).then((result) =>
        result.ok && result.value.attemptId !== attemptId
          ? failed({ kind: "invalid-response" })
          : result,
      );
    },
    abandon(attemptId, input, key, signal) {
      if (
        !validId(attemptId) ||
        !abandonRequestSchema.safeParse(input).success ||
        !keySchema.safeParse(key).success
      )
        return Promise.resolve(failed({ kind: "invalid-input" }));
      return execute({
        method: "POST",
        path: `/api/assessments/attempts/${attemptId}/abandon`,
        body: input,
        key,
        schema: abandonAckSchema,
        signal,
      }).then((result) =>
        result.ok && result.value.attemptId !== attemptId
          ? failed({ kind: "invalid-response" })
          : result,
      );
    },
    getResult(attemptId, signal) {
      if (!validId(attemptId))
        return Promise.resolve(failed({ kind: "invalid-input" }));
      return execute({
        method: "GET",
        path: `/api/assessments/attempts/${attemptId}/result`,
        schema: resultSchema,
        signal,
      }).then((result) =>
        result.ok && result.value.attemptId !== attemptId
          ? failed({ kind: "invalid-response" })
          : result,
      );
    },
    getAggregate(publicationId, signal) {
      if (!validId(publicationId))
        return Promise.resolve(failed({ kind: "invalid-input" }));
      return execute({
        method: "GET",
        path: `/api/assessments/publications/${publicationId}/aggregate-score`,
        schema: aggregateSchema,
        signal,
      }).then((result) =>
        result.ok && result.value.publicationId !== publicationId
          ? failed({ kind: "invalid-response" })
          : result,
      );
    },
    getReview(attemptId, signal) {
      if (!validId(attemptId))
        return Promise.resolve(failed({ kind: "invalid-input" }));
      return execute({
        method: "GET",
        path: `/api/assessments/attempts/${attemptId}/review`,
        schema: reviewSchema,
        signal,
      }).then((result) =>
        result.ok && result.value.result.attemptId !== attemptId
          ? failed({ kind: "invalid-response" })
          : result,
      );
    },
  } satisfies AttemptApiClient);
}

export type { Answer };
