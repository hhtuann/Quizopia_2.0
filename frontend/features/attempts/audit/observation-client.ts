import { z } from "zod";
import type { AuthenticatedRequestExecutor } from "../../../lib/api/authenticated-request";
import { publicApiTarget } from "../../../lib/api/public-api";
import type {
  AnswerObservation,
  ObservationChoice,
  ObservationChoiceRequest,
} from "../../teacher-results/api/teacher-assessment-contract.generated";
import { canonicalUuid } from "../model/attempt";
import { resultSchema, questionReviewSchema } from "../model/attempt";
import type {
  AuditTimeline,
  TeacherAttemptReview,
} from "../../teacher-results/api/teacher-assessment-contract.generated";

const instant = z.string().datetime({ offset: true });
const transition = z
  .object({
    fromOptionLabels: z.array(z.enum(["A", "B", "C", "D"])).max(4),
    toOptionLabels: z.array(z.enum(["A", "B", "C", "D"])).max(4),
  })
  .strict();
const reviewSchema: z.ZodType<TeacherAttemptReview> = z
  .object({
    publicationId: canonicalUuid,
    attemptId: canonicalUuid,
    snapshotId: canonicalUuid,
    state: z.literal("SUBMITTED"),
    revision: z.string().regex(/^[0-9]+$/),
    gradingPolicyVersion: z.literal(1),
    startedAt: instant,
    finalizedAt: instant,
    result: resultSchema,
    questions: z.array(questionReviewSchema).min(1),
  })
  .strict();
const timelineSchema: z.ZodType<AuditTimeline> = z
  .object({
    items: z
      .array(
        z
          .object({
            eventId: canonicalUuid,
            ordinal: z.string().regex(/^[1-9][0-9]*$/),
            type: z.enum([
              "ATTEMPT_STARTED",
              "ANSWER_PERSISTED",
              "ATTEMPT_SUBMITTED",
              "ATTEMPT_ABANDONED",
              "CLOSURE_NOTICE_CONFIRMED",
              "RESULT_GRADED",
              "QUESTION_VIEWED",
              "QUESTION_LEFT",
              "ANSWER_SELECTED",
              "ANSWER_CHANGED",
              "ANSWER_CLEARED",
            ]),
            source: z.enum(["SERVER_AUTHORITY", "CLIENT_OBSERVATION"]),
            questionNumber: z.number().int().positive().nullable(),
            observedAt: instant.nullable(),
            occurredAt: instant.nullable(),
            assessmentRevision: z.string().nullable(),
            operationKind: z
              .enum([
                "START",
                "SAVE",
                "FINALIZATION",
                "ABANDON",
                "NOTICE_CONFIRMATION",
              ])
              .nullable(),
            terminalReason: z
              .enum([
                "MANUAL_SUBMIT",
                "BOUNDED_TIMEOUT",
                "TEACHER_NOTICE_CUTOFF",
                "STUDENT_ABANDON",
              ])
              .nullable(),
            resultId: canonicalUuid.nullable(),
            noticeId: canonicalUuid.nullable(),
            receivedAt: instant,
            transitionDetailStatus: z.enum([
              "AVAILABLE",
              "UNSUPPORTED_TYPE",
              "MISSING",
              "EXPIRED",
              "NOT_APPLICABLE",
            ]),
            transition: transition.nullable(),
          })
          .strict(),
      )
      .max(100),
    nextAfter: z.string().nullable(),
    coverage: z
      .object({
        status: z.enum(["NOT_COLLECTED", "PARTIAL"]),
        reasons: z
          .array(
            z.enum([
              "COLLECTION_OFF",
              "LEGACY",
              "UNSUPPORTED_TYPE",
              "CLIENT_GAP",
              "EXPIRED",
            ]),
          )
          .max(5),
      })
      .strict(),
  })
  .strict();

export type SensitiveReadResult<T> =
  | { readonly ok: true; readonly value: T }
  | {
      readonly ok: false;
      readonly status: number;
      readonly code: string | null;
    };

const failureEnvelope = z.object({ code: z.string().optional() }).loose();

const choiceSchema: z.ZodType<ObservationChoice> = z
  .object({
    available: z.boolean(),
    enabled: z.boolean(),
    noticeVersion: z.literal("ACADEMIC_DEMO_V1"),
    decidedAt: z.string().datetime({ offset: true }).nullable(),
    streamId: canonicalUuid.nullable(),
  })
  .strict();

export function observationClient(
  executor: AuthenticatedRequestExecutor,
  generation: () => number | null,
  publication: string,
  attempt: string,
) {
  const bound = generation();
  const base = `/api/assessments/publications/${publication}/attempts/${attempt}`;
  async function request(
    method: "GET" | "PUT" | "POST",
    suffix: string,
    body: unknown,
    signal: AbortSignal,
  ) {
    if (
      bound === null ||
      generation() !== bound ||
      signal.aborted ||
      !canonicalUuid.safeParse(publication).success ||
      !canonicalUuid.safeParse(attempt).success
    )
      return { status: 401, body: null };
    const timer = new AbortController();
    const cancel = () => timer.abort();
    signal.addEventListener("abort", cancel, { once: true });
    const timeout = setTimeout(cancel, 5000);
    try {
      const result = await executor.executeOnce({
        signal: timer.signal,
        createRequest: () => {
          if (generation() !== bound || timer.signal.aborted)
            throw new DOMException("Obsolete optional history", "AbortError");
          return {
            method,
            target: publicApiTarget(`${base}/${suffix}`),
            body: body === undefined ? undefined : JSON.stringify(body),
            headers: {
              accept: "application/json",
              ...(body === undefined
                ? {}
                : { "content-type": "application/json" }),
            },
          };
        },
      });
      if (generation() !== bound || signal.aborted)
        return { status: 401, body: null };
      if (result.kind !== "response")
        return {
          status:
            result.kind === "authentication-failure"
              ? 401
              : result.kind === "transport-error" &&
                  result.error.kind === "invalid-response" &&
                  result.error.status !== undefined
                ? result.error.status
                : 503,
          body: null,
        };
      return {
        status: result.response.status,
        body:
          result.response.body.kind === "json"
            ? result.response.body.value
            : null,
      };
    } catch {
      return { status: signal.aborted ? 401 : 503, body: null };
    } finally {
      clearTimeout(timeout);
      signal.removeEventListener("abort", cancel);
    }
  }
  function denied(response: {
    readonly status: number;
    readonly body: unknown;
  }): SensitiveReadResult<never> {
    const envelope = failureEnvelope.safeParse(response.body);
    return {
      ok: false,
      status: response.status,
      code:
        envelope.success && envelope.data.code !== undefined
          ? envelope.data.code
          : null,
    };
  }
  return {
    async review(
      signal: AbortSignal,
    ): Promise<SensitiveReadResult<TeacherAttemptReview>> {
      const response = await request(
        "GET",
        "teacher-review",
        undefined,
        signal,
      );
      const parsed = reviewSchema.safeParse(response.body);
      return response.status === 200 &&
        parsed.success &&
        parsed.data.publicationId === publication &&
        parsed.data.attemptId === attempt &&
        parsed.data.result.attemptId === attempt
        ? { ok: true, value: parsed.data }
        : denied(response);
    },
    async timeline(
      after: string,
      signal: AbortSignal,
    ): Promise<SensitiveReadResult<AuditTimeline>> {
      if (!/^(0|[1-9][0-9]*)$/.test(after))
        return { ok: false, status: 400, code: "INVALID_REQUEST" };
      const response = await request(
        "GET",
        `answer-audit?after=${after}&limit=50`,
        undefined,
        signal,
      );
      const parsed = timelineSchema.safeParse(response.body);
      return response.status === 200 && parsed.success
        ? { ok: true, value: parsed.data }
        : denied(response);
    },
    async choice(
      enabled: boolean | undefined,
      signal: AbortSignal,
    ): Promise<ObservationChoice | null> {
      const body: ObservationChoiceRequest | undefined =
        enabled === undefined
          ? undefined
          : { enabled, noticeVersion: "ACADEMIC_DEMO_V1" };
      const response = await request(
        enabled === undefined ? "GET" : "PUT",
        "answer-observation-choice",
        body,
        signal,
      );
      const parsed = choiceSchema.safeParse(response.body);
      return response.status === 200 && parsed.success ? parsed.data : null;
    },
    async send(event: AnswerObservation, signal: AbortSignal) {
      const response = await request(
        "POST",
        "answer-observations",
        event,
        signal,
      );
      if (response.status === 200) {
        const ack = z
          .object({
            eventId: canonicalUuid,
            ordinal: z.string().regex(/^[1-9][0-9]*$/),
            receivedAt: z.string().datetime({ offset: true }),
          })
          .strict()
          .safeParse(response.body);
        if (!ack.success || ack.data.eventId !== event.eventId) return 400;
      }
      return response.status;
    },
  };
}
