import type {
  AssessmentFailure,
  AssessmentRequester,
  AssessmentResult,
} from "../../assessments/api/assessment-foundation";
import {
  createAssessmentRequester,
  idempotencyKeySchema,
} from "../../assessments/api/assessment-foundation";
import type {
  ClosureCompletion,
  ClosureView,
  TeacherCommand,
} from "../../assessments/api/publication-contract.generated";
import { canonicalUuid } from "../../assessments/model/publication";
import {
  closureCompletionSchema,
  closureViewSchema,
  teacherCommandSchema,
} from "../model/closure";

export type {
  AssessmentFailure as ClosureFailure,
  AssessmentResult as ClosureResult,
};

export interface ClosureApiClient {
  /**
   * POST /publications/{publicationId}/closure-requests.
   * 201 creates a durable pending request; a matching idempotent replay
   * returns 200 with the same request's current view. Neither means CLOSED.
   */
  request(
    publicationId: string,
    input: TeacherCommand,
    key: string,
    signal?: AbortSignal,
  ): Promise<
    AssessmentResult<{ readonly view: ClosureView; readonly created: boolean }>
  >;
  /** GET /publications/{publicationId}/closure-requests/{closureRequestId}. */
  get(
    publicationId: string,
    closureRequestId: string,
    signal?: AbortSignal,
  ): Promise<AssessmentResult<ClosureView>>;
  cancel(
    publicationId: string,
    closureRequestId: string,
    input: TeacherCommand,
    key: string,
    signal?: AbortSignal,
  ): Promise<AssessmentResult<ClosureView>>;
  /**
   * POST .../complete. The body is authoritative: closed=true only when the
   * durable CLOSED transition committed; 202 carries committed bounded
   * progress with closed=false — inspect GET and continue with a new key.
   */
  complete(
    publicationId: string,
    closureRequestId: string,
    input: TeacherCommand,
    key: string,
    signal?: AbortSignal,
  ): Promise<AssessmentResult<ClosureCompletion>>;
}

export function createClosureApiClient(options: {
  readonly authenticatedRequests: Parameters<
    typeof createAssessmentRequester
  >[0]["authenticatedRequests"];
  readonly getSessionGeneration: () => number | null;
  readonly apiBaseUrl?: string;
}): ClosureApiClient {
  const requester: AssessmentRequester = createAssessmentRequester(options);

  function validIds(
    publicationId: string,
    closureRequestId: string | null,
  ): boolean {
    return (
      canonicalUuid.safeParse(publicationId).success &&
      (closureRequestId === null ||
        canonicalUuid.safeParse(closureRequestId).success)
    );
  }

  function validCommand(
    publicationId: string,
    closureRequestId: string | null,
    input: TeacherCommand,
    key: string,
  ): boolean {
    return (
      validIds(publicationId, closureRequestId) &&
      teacherCommandSchema.safeParse(input).success &&
      idempotencyKeySchema.safeParse(key).success
    );
  }

  const invalid = <T>(): AssessmentResult<T> => ({
    ok: false,
    error: { kind: "invalid-input" },
  });

  function bindView(
    publicationId: string,
    closureRequestId: string | null,
    result: AssessmentResult<ClosureView>,
  ): AssessmentResult<ClosureView> {
    return result.ok &&
      (result.value.publicationId !== publicationId ||
        (closureRequestId !== null &&
          result.value.closureRequestId !== closureRequestId))
      ? invalid()
      : result;
  }

  return Object.freeze({
    request(publicationId, input, key, signal) {
      if (!validCommand(publicationId, null, input, key))
        return Promise.resolve(invalid());
      return requester
        .execute({
          method: "POST",
          path: `/publications/${publicationId}/closure-requests`,
          schema: closureViewSchema,
          body: input,
          key,
          signal,
          successStatuses: [201],
        })
        .then((result) => {
          const bound = bindView(publicationId, null, result);
          return bound.ok
            ? {
                ok: true,
                status: bound.status,
                value: {
                  view: bound.value,
                  created: bound.status === 201,
                },
              }
            : bound;
        });
    },
    get(publicationId, closureRequestId, signal) {
      if (!validIds(publicationId, closureRequestId))
        return Promise.resolve(invalid());
      return requester
        .execute({
          method: "GET",
          path: `/publications/${publicationId}/closure-requests/${closureRequestId}`,
          schema: closureViewSchema,
          signal,
        })
        .then((result) => bindView(publicationId, closureRequestId, result));
    },
    cancel(publicationId, closureRequestId, input, key, signal) {
      if (!validCommand(publicationId, closureRequestId, input, key))
        return Promise.resolve(invalid());
      return requester
        .execute({
          method: "POST",
          path: `/publications/${publicationId}/closure-requests/${closureRequestId}/cancel`,
          schema: closureViewSchema,
          body: input,
          key,
          signal,
        })
        .then((result) => bindView(publicationId, closureRequestId, result));
    },
    complete(publicationId, closureRequestId, input, key, signal) {
      if (!validCommand(publicationId, closureRequestId, input, key))
        return Promise.resolve(invalid());
      return requester
        .execute({
          method: "POST",
          path: `/publications/${publicationId}/closure-requests/${closureRequestId}/complete`,
          schema: closureCompletionSchema,
          body: input,
          key,
          signal,
          successStatuses: [202],
          // COMPLETE may durably process up to fifty finalization units before
          // answering; a client timeout stays an uncertain outcome, never a
          // silent second key.
          timeoutMs: 30_000,
        })
        .then((result) =>
          result.ok &&
          (result.value.publicationId !== publicationId ||
            result.value.closureRequestId !== closureRequestId)
            ? invalid()
            : result,
        );
    },
  } satisfies ClosureApiClient);
}
