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

const draftSchema = z
  .object({
    quizId: z.string().uuid(),
    title: z.string().nullable(),
    description: z.string().nullable(),
    authoringSource: z.string().nullable(),
    createdAt: z.string(),
    updatedAt: z.string(),
  })
  .strict();

const versionSchema = z
  .object({
    id: z.string().uuid(),
    quizId: z.string().uuid(),
    versionNumber: z.number().int().positive(),
    createdAt: z.string(),
  })
  .strict();

const libraryItemSchema = z
  .object({
    quizId: z.string().uuid(),
    title: z.string().nullable(),
    description: z.string().nullable(),
    createdAt: z.string(),
    updatedAt: z.string(),
    latestVersionNumber: z.number().int().positive().nullable(),
  })
  .strict();

const libraryPageSchema = z
  .object({
    items: z.array(libraryItemSchema),
    nextCursor: z.string().nullable(),
  })
  .strict();

const apiErrorSchema = z
  .object({
    code: z.string(),
    message: z.string(),
    status: z.number().int(),
    path: z.string(),
  })
  .strict();

const markdownErrorSchema = z
  .object({
    code: z.string(),
    questionNumber: z.number().int().nullable(),
    line: z.number().int().nullable(),
    column: z.number().int().nullable(),
    message: z.string(),
  })
  .strict();

const markdownValidationSchema = z
  .object({
    code: z.literal("QUIZ_MARKDOWN_INVALID"),
    message: z.string(),
    status: z.literal(400),
    path: z.string(),
    traceId: z.string(),
    errors: z.array(markdownErrorSchema),
  })
  .strict();

export type QuizDraft = z.infer<typeof draftSchema>;
export type QuizVersion = z.infer<typeof versionSchema>;
export type QuizLibraryItem = z.infer<typeof libraryItemSchema>;
export type QuizLibraryPage = z.infer<typeof libraryPageSchema>;
export type QuizMarkdownServerError = z.infer<typeof markdownErrorSchema>;

export interface QuizApiErrorEnvelope {
  readonly code: string;
  readonly message: string;
  readonly path: string;
  readonly status: number;
}

export type QuizApiFailure =
  | { readonly kind: "api-error"; readonly error: QuizApiErrorEnvelope }
  | {
      readonly kind: "validation-error";
      readonly errors: readonly QuizMarkdownServerError[];
      readonly traceId: string;
    }
  | { readonly kind: "authentication-failure"; readonly reason: string }
  | { readonly kind: "transport-error"; readonly error: HttpTransportError }
  | { readonly kind: "unexpected-response"; readonly status: number };

export type QuizApiResult<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: QuizApiFailure };

export interface QuizDraftInput {
  readonly authoringSource: string;
  readonly description: string;
  readonly title: string;
}

export interface QuizLibraryQuery {
  readonly cursor?: string;
  readonly limit?: number;
}

export interface QuizApiClient {
  createDraft(
    input: QuizDraftInput,
    signal?: AbortSignal,
  ): Promise<QuizApiResult<QuizDraft>>;
  getDraft(
    quizId: string,
    signal?: AbortSignal,
  ): Promise<QuizApiResult<QuizDraft>>;
  listOwnedQuizzes(
    query?: QuizLibraryQuery,
    signal?: AbortSignal,
  ): Promise<QuizApiResult<QuizLibraryPage>>;
  publishDraft(
    quizId: string,
    signal?: AbortSignal,
  ): Promise<
    QuizApiResult<{ readonly created: boolean; readonly version: QuizVersion }>
  >;
  updateDraft(
    quizId: string,
    input: QuizDraftInput,
    signal?: AbortSignal,
  ): Promise<QuizApiResult<QuizDraft>>;
}

function normalizeAuthenticatedResult(
  result: AuthenticatedRequestResult,
): QuizApiResult<HttpResponse> {
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
  return { ok: true, value: result.response };
}

function parseFailure(response: HttpResponse): QuizApiResult<never> {
  if (response.body.kind === "json") {
    const validation = markdownValidationSchema.safeParse(response.body.value);
    if (validation.success && validation.data.status === response.status) {
      return {
        ok: false,
        error: {
          kind: "validation-error",
          errors: validation.data.errors,
          traceId: validation.data.traceId,
        },
      };
    }
    const regular = apiErrorSchema.safeParse(response.body.value);
    if (regular.success && regular.data.status === response.status) {
      return { ok: false, error: { kind: "api-error", error: regular.data } };
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
): QuizApiResult<T> {
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

export function createQuizApiClient(options: {
  readonly authenticatedRequests: AuthenticatedRequestExecutor;
  readonly apiBaseUrl?: string;
}): QuizApiClient {
  const { authenticatedRequests, apiBaseUrl } = options;
  const target = (path: string) => publicApiTarget(path, apiBaseUrl);

  async function execute(
    path: string,
    method: "GET" | "POST" | "PUT",
    body: QuizDraftInput | undefined,
    signal?: AbortSignal,
  ): Promise<QuizApiResult<HttpResponse>> {
    return normalizeAuthenticatedResult(
      await authenticatedRequests.execute({
        createRequest: () => ({
          body: body === undefined ? undefined : JSON.stringify(body),
          headers: new Headers(
            body === undefined
              ? { accept: "application/json" }
              : {
                  accept: "application/json",
                  "content-type": "application/json",
                },
          ),
          method,
          target: target(path),
        }),
        signal,
      }),
    );
  }

  const client: QuizApiClient = {
    async createDraft(input, signal) {
      const result = await execute("/api/quizzes", "POST", input, signal);
      return result.ok ? expectJson(result.value, [201], draftSchema) : result;
    },
    async getDraft(quizId, signal) {
      const result = await execute(
        `/api/quizzes/${encodeURIComponent(quizId)}/draft`,
        "GET",
        undefined,
        signal,
      );
      return result.ok ? expectJson(result.value, [200], draftSchema) : result;
    },
    async listOwnedQuizzes(query = {}, signal) {
      const parameters = new URLSearchParams();
      if (query.limit !== undefined) {
        parameters.set("limit", String(query.limit));
      }
      if (query.cursor !== undefined) {
        parameters.set("cursor", query.cursor);
      }
      const suffix = parameters.size > 0 ? `?${parameters.toString()}` : "";
      const result = await execute(
        `/api/quizzes${suffix}`,
        "GET",
        undefined,
        signal,
      );
      return result.ok
        ? expectJson(result.value, [200], libraryPageSchema)
        : result;
    },
    async publishDraft(quizId, signal) {
      const result = await execute(
        `/api/quizzes/${encodeURIComponent(quizId)}/versions`,
        "POST",
        undefined,
        signal,
      );
      if (!result.ok) {
        return result;
      }
      const parsed = expectJson(result.value, [200, 201], versionSchema);
      return parsed.ok
        ? {
            ok: true,
            value: {
              created: result.value.status === 201,
              version: parsed.value,
            },
          }
        : parsed;
    },
    async updateDraft(quizId, input, signal) {
      const result = await execute(
        `/api/quizzes/${encodeURIComponent(quizId)}/draft`,
        "PUT",
        input,
        signal,
      );
      return result.ok ? expectJson(result.value, [200], draftSchema) : result;
    },
  };
  return Object.freeze(client);
}
