import { z } from "zod";
import type {
  AbandonAck,
  AbandonRequest,
  Aggregate,
  Answer,
  AnswerEntry,
  AttemptClosure,
  AttemptView as AttemptViewContract,
  Entry,
  MatrixAnswer,
  MultipleAnswer,
  NumericAnswer,
  Option,
  Question,
  QuestionReview,
  Result,
  Review,
  SaveAck,
  SaveRequest,
  SingleAnswer,
  StartRequest,
  StudentView as StudentViewContract,
  SubmitRequest,
} from "../api/attempt-contract.generated";
import { instantMicroseconds } from "../../assessments/utils/utc-time";

export const canonicalUuid = z
  .string()
  .regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
const MAX_SIGNED_LONG = BigInt("9223372036854775807");
/** Attempt revisions and ordinals are canonical decimal strings; never numbers. */
export const attemptRevisionSchema = z
  .string()
  .regex(/^(0|[1-9][0-9]{0,18})$/)
  .refine((value) => BigInt(value) <= MAX_SIGNED_LONG);
export const attemptOrdinalSchema = z
  .string()
  .regex(/^[1-9][0-9]{0,18}$/)
  .refine((value) => BigInt(value) <= MAX_SIGNED_LONG);
export const privateLocatorSchema = z.string().regex(/^[A-Za-z0-9_-]{43}$/);
export const optionLabelSchema = z.enum(["A", "B", "C", "D"]);
export const instantSchema = z
  .string()
  .refine(
    (value) => instantMicroseconds(value) !== null,
    "Use a valid instant with an explicit offset and at most six fractional digits.",
  );
export const durationSchema = z.union([
  z.literal(0),
  z.number().int().min(5).max(240),
]);

/**
 * NUMERIC_FILL accepted exactly-four-character token grammar, mirrored from the
 * implemented OpenAPI NumericAnswer pattern: `dddd`, `-ddd`, `dd.dd`, `d.dd`,
 * `-d.d` or `-.dd`. No normalization: `0001` and `1.00` stay distinct.
 */
export const NUMERIC_TOKEN_PATTERN =
  "[0-9]{4}|-[0-9]{3}|[0-9][.][0-9]{2}|[0-9]{2}[.][0-9]|-[0-9][.][0-9]|-[.][0-9]{2]";
const numericTokenRegex =
  /^(?:[0-9]{4}|-[0-9]{3}|[0-9][.][0-9]{2}|[0-9]{2}[.][0-9]|-[0-9][.][0-9]|-[.][0-9]{2})$/;
/** Outer ASCII whitespace only, optional token; the wire shape the server emits. */
const numericWireRegex = new RegExp(
  "^[\\u0009-\\u000d\\u0020]*(?:[0-9]{4}|-[0-9]{3}|[0-9][.][0-9]{2}|[0-9]{2}[.][0-9]|-[0-9][.][0-9]|-[.][0-9]{2})?[\\u0009-\\u000d\\u0020]*$",
);

export function isValidNumericToken(value: string): boolean {
  return numericTokenRegex.test(value);
}

/** Trim only outer ASCII whitespace; blank becomes unanswered (null). */
export function normalizeNumericInput(raw: string): string | null {
  const trimmed = raw.replace(/^[\u0009-\u000d ]+|[\u0009-\u000d ]+$/g, "");
  return trimmed.length === 0 ? null : trimmed;
}

export const singleAnswerSchema = z
  .object({
    type: z.literal("SINGLE_CHOICE"),
    optionLabel: optionLabelSchema.nullable(),
  })
  .strict() satisfies z.ZodType<SingleAnswer>;
export const multipleAnswerSchema = z
  .object({
    type: z.literal("MULTIPLE_CHOICE"),
    optionLabels: z
      .array(optionLabelSchema)
      .max(4)
      .refine(
        (labels) => new Set(labels).size === labels.length,
        "Option labels must be unique.",
      ),
  })
  .strict() satisfies z.ZodType<MultipleAnswer>;
export const matrixJudgmentsSchema = z
  .object({
    A: z.boolean().nullable(),
    B: z.boolean().nullable(),
    C: z.boolean().nullable(),
    D: z.boolean().nullable(),
  })
  .strict();
export const matrixAnswerSchema = z
  .object({
    type: z.literal("TRUE_FALSE_MATRIX"),
    judgments: matrixJudgmentsSchema,
  })
  .strict() satisfies z.ZodType<MatrixAnswer>;
export const numericAnswerSchema = z
  .object({
    type: z.literal("NUMERIC_FILL"),
    value: z
      .string()
      .max(64)
      .nullable()
      .refine(
        (value) => value === null || numericWireRegex.test(value),
        "NUMERIC_FILL accepts only the exact four-character numeric grammar or null.",
      ),
  })
  .strict() satisfies z.ZodType<NumericAnswer>;
export const answerSchema = z.discriminatedUnion("type", [
  singleAnswerSchema,
  multipleAnswerSchema,
  matrixAnswerSchema,
  numericAnswerSchema,
]) satisfies z.ZodType<Answer>;

export const optionSchema = z
  .object({ label: optionLabelSchema, markdown: z.string().min(1) })
  .strict() satisfies z.ZodType<Option>;
export const questionSchema = z
  .object({
    number: z.number().int().min(1),
    type: z.enum([
      "SINGLE_CHOICE",
      "MULTIPLE_CHOICE",
      "TRUE_FALSE_MATRIX",
      "NUMERIC_FILL",
    ]),
    stemMarkdown: z.string().min(1),
    options: z.array(optionSchema).max(4),
  })
  .strict() satisfies z.ZodType<Question>;
export const answerEntrySchema = z
  .object({
    questionNumber: z.number().int().min(1),
    answer: answerSchema,
  })
  .strict() satisfies z.ZodType<AnswerEntry>;

export const attemptClosureSchema = z
  .object({
    closureRequestId: canonicalUuid,
    confirmedNoticeAt: instantSchema.nullable(),
    closureCutoffAt: instantSchema.nullable(),
  })
  .strict() satisfies z.ZodType<AttemptClosure>;

export const attemptViewSchema = z
  .object({
    attemptId: canonicalUuid,
    publicationId: canonicalUuid,
    ordinal: attemptOrdinalSchema,
    state: z.enum(["ACTIVE", "SUBMITTED", "ABANDONED"]),
    startedAt: instantSchema,
    deadlineAt: instantSchema.nullable(),
    durationMinutes: durationSchema,
    revision: attemptRevisionSchema,
    serverNow: instantSchema,
    questions: z.array(questionSchema).min(1),
    answers: z.array(answerEntrySchema).min(1),
    closure: attemptClosureSchema.nullable(),
  })
  .strict()
  .superRefine((value, context) => {
    const questionByNumber = new Map(
      value.questions.map((question) => [question.number, question]),
    );
    const numbers = new Set(questionByNumber.keys());
    if (numbers.size !== value.questions.length) {
      context.addIssue({
        code: "custom",
        path: ["questions"],
        message: "Question numbers must be unique.",
      });
      return;
    }
    for (const [index, question] of value.questions.entries()) {
      if (question.number !== index + 1) {
        context.addIssue({
          code: "custom",
          path: ["questions", index, "number"],
          message: "Question numbers must be continuous and in snapshot order.",
        });
        return;
      }
      const labels = question.options.map((option) => option.label);
      if (new Set(labels).size !== labels.length) {
        context.addIssue({
          code: "custom",
          path: ["questions", index, "options"],
          message: "Question option labels must be unique.",
        });
        return;
      }
      if (question.type === "TRUE_FALSE_MATRIX" && labels.join("") !== "ABCD") {
        context.addIssue({
          code: "custom",
          path: ["questions", index, "options"],
          message: "Matrix questions must contain statements A, B, C and D.",
        });
        return;
      }
      if (question.type === "NUMERIC_FILL" && labels.length !== 0) {
        context.addIssue({
          code: "custom",
          path: ["questions", index, "options"],
          message: "Numeric questions must not contain options.",
        });
        return;
      }
    }
    const answered = new Set<number>();
    for (const entry of value.answers) {
      const question = questionByNumber.get(entry.questionNumber);
      if (question === undefined) {
        context.addIssue({
          code: "custom",
          path: ["answers"],
          message: "Answer references an unknown question.",
        });
        return;
      }
      if (entry.answer.type !== question.type) {
        context.addIssue({
          code: "custom",
          path: ["answers"],
          message: "Answer tag must match its snapshot question type.",
        });
        return;
      }
      if (answered.has(entry.questionNumber)) {
        context.addIssue({
          code: "custom",
          path: ["answers"],
          message: "Duplicate answer for one question.",
        });
        return;
      }
      answered.add(entry.questionNumber);
    }
    for (const number of numbers) {
      if (!answered.has(number)) {
        context.addIssue({
          code: "custom",
          path: ["answers"],
          message: "Every snapshot question must carry one typed answer entry.",
        });
        return;
      }
    }
  }) satisfies z.ZodType<AttemptViewContract>;

export const entrySchema = z
  .object({ locator: privateLocatorSchema })
  .strict() satisfies z.ZodType<Entry>;
export const startRequestSchema = z
  .object({ locator: privateLocatorSchema.optional() })
  .strict() satisfies z.ZodType<StartRequest>;
export const studentViewSchema = z
  .object({
    publicationId: canonicalUuid,
    title: z.string().min(1).max(300),
    description: z.string().max(5000).nullable(),
    audience: z.enum(["CLASS", "PUBLIC"]),
    startAt: instantSchema,
    endAt: instantSchema,
    durationMinutes: durationSchema,
    monitoringRequired: z.boolean(),
    windowStatus: z.enum(["BEFORE_START", "AVAILABLE", "WINDOW_ENDED"]),
  })
  .strict()
  .superRefine((value, context) => {
    const start = instantMicroseconds(value.startAt);
    const end = instantMicroseconds(value.endAt);
    if (start !== null && end !== null && start >= end) {
      context.addIssue({
        code: "custom",
        path: ["endAt"],
        message: "Participation end must be after start.",
      });
    }
  }) satisfies z.ZodType<StudentViewContract>;
export const saveRequestSchema = z
  .object({ expectedRevision: attemptRevisionSchema, answer: answerSchema })
  .strict() satisfies z.ZodType<SaveRequest>;
export const submitRequestSchema = z
  .object({
    expectedRevision: attemptRevisionSchema,
    answers: z.array(answerEntrySchema).min(1).optional(),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.answers === undefined) return;
    const seen = new Set<number>();
    for (const entry of value.answers) {
      if (seen.has(entry.questionNumber)) {
        context.addIssue({
          code: "custom",
          path: ["answers"],
          message: "Complete answers must contain each question exactly once.",
        });
        return;
      }
      seen.add(entry.questionNumber);
    }
  }) satisfies z.ZodType<SubmitRequest>;
export const abandonRequestSchema = z
  .object({ expectedRevision: attemptRevisionSchema })
  .strict() satisfies z.ZodType<AbandonRequest>;
export const saveAckSchema = z
  .object({
    attemptId: canonicalUuid,
    questionNumber: z.number().int().min(1),
    revision: attemptRevisionSchema,
    savedAt: instantSchema,
  })
  .strict() satisfies z.ZodType<SaveAck>;
export const abandonAckSchema = z
  .object({
    attemptId: canonicalUuid,
    state: z.literal("ABANDONED"),
    terminalReason: z.literal("STUDENT_ABANDON"),
    revision: attemptRevisionSchema,
    finalizedAt: instantSchema,
  })
  .strict() satisfies z.ZodType<AbandonAck>;
export const resultSchema = z
  .object({
    resultId: canonicalUuid,
    attemptId: canonicalUuid,
    publicationId: canonicalUuid,
    ordinal: attemptOrdinalSchema,
    earnedWeight: z.string().regex(/^(0|[1-9][0-9]*)(\.[0-9]*[1-9])?$/),
    possibleWeight: z.string().regex(/^[1-9][0-9]*$/),
    percentageDisplay: z
      .string()
      .regex(/^(100\.00|(?:[0-9]|[1-9][0-9])\.[0-9]{2})$/),
    gradingPolicyVersion: z.literal(1),
    gradingPolicyId: z.literal("QUIZOPIA_GRADING_V1"),
    terminalReason: z.enum([
      "MANUAL_SUBMIT",
      "BOUNDED_TIMEOUT",
      "TEACHER_NOTICE_CUTOFF",
    ]),
    finalizedAt: instantSchema,
  })
  .strict() satisfies z.ZodType<Result>;
export const aggregateSchema = z
  .object({
    publicationId: canonicalUuid,
    bestResult: resultSchema.nullable(),
  })
  .strict() satisfies z.ZodType<Aggregate>;
export const questionReviewSchema = z
  .object({
    question: questionSchema,
    answer: answerSchema,
    correctAnswer: answerSchema,
    earnedWeight: z.string().regex(/^(0|[1-9][0-9]*)(\.[0-9]*[1-9])?$/),
    possibleWeight: z.literal("1"),
    explanationMarkdown: z.string().nullable(),
  })
  .strict() satisfies z.ZodType<QuestionReview>;
export const reviewSchema = z
  .object({
    result: resultSchema,
    questions: z.array(questionReviewSchema).min(1),
  })
  .strict()
  .superRefine((value, context) => {
    // possibleWeight is the question count encoded as an exact decimal string.
    let possible: bigint;
    try {
      possible = BigInt(value.result.possibleWeight);
    } catch {
      possible = BigInt(-1);
    }
    if (possible !== BigInt(value.questions.length)) {
      context.addIssue({
        code: "custom",
        path: ["questions"],
        message: "Review question count must match the result possible weight.",
      });
    }
  }) satisfies z.ZodType<Review>;

export type AttemptView = AttemptViewContract;
export type StudentView = StudentViewContract;
export type AttemptAnswer = Answer;
export type AttemptResult = Result;
export type AttemptReview = Review;

export const ATTEMPT_QUESTION_PAGE_SIZE = 100;

export function boundedQuestionPage(
  totalQuestions: number,
  requestedPage: number,
): { readonly page: number; readonly start: number; readonly end: number } {
  const pageCount = Math.max(
    1,
    Math.ceil(totalQuestions / ATTEMPT_QUESTION_PAGE_SIZE),
  );
  const page = Math.min(Math.max(0, requestedPage), pageCount - 1);
  const start = page * ATTEMPT_QUESTION_PAGE_SIZE;
  return {
    page,
    start,
    end: Math.min(totalQuestions, start + ATTEMPT_QUESTION_PAGE_SIZE),
  };
}

/** Unanswered semantics per the answer union: null, empty set or all-null matrix. */
export function isUnanswered(answer: Answer): boolean {
  switch (answer.type) {
    case "SINGLE_CHOICE":
      return answer.optionLabel === null;
    case "MULTIPLE_CHOICE":
      return answer.optionLabels.length === 0;
    case "TRUE_FALSE_MATRIX":
      return (
        answer.judgments.A === null &&
        answer.judgments.B === null &&
        answer.judgments.C === null &&
        answer.judgments.D === null
      );
    case "NUMERIC_FILL":
      return (
        answer.value === null || normalizeNumericInput(answer.value) === null
      );
  }
}

export function effectiveAttemptDeadline(
  attempt: Pick<AttemptView, "deadlineAt" | "closure">,
): string | null {
  const candidates = [
    attempt.deadlineAt,
    attempt.closure?.closureCutoffAt ?? null,
  ].filter((value): value is string => value !== null);
  return candidates.reduce<string | null>(
    (earliest, value) =>
      earliest === null || Date.parse(value) < Date.parse(earliest)
        ? value
        : earliest,
    null,
  );
}

/** Structural equality on canonical wire values; used to skip redundant saves. */
export function sameAnswer(left: Answer, right: Answer): boolean {
  if (left.type !== right.type) return false;
  switch (left.type) {
    case "SINGLE_CHOICE":
      return left.optionLabel === (right as SingleAnswer).optionLabel;
    case "MULTIPLE_CHOICE": {
      const other = right as MultipleAnswer;
      const sortedLeft = [...left.optionLabels].sort();
      const sortedRight = [...other.optionLabels].sort();
      return (
        sortedLeft.length === sortedRight.length &&
        sortedLeft.every((label, index) => label === sortedRight[index])
      );
    }
    case "TRUE_FALSE_MATRIX": {
      const other = right as MatrixAnswer;
      return (
        left.judgments.A === other.judgments.A &&
        left.judgments.B === other.judgments.B &&
        left.judgments.C === other.judgments.C &&
        left.judgments.D === other.judgments.D
      );
    }
    case "NUMERIC_FILL":
      return (
        normalizeNumericInput(left.value ?? "") ===
        normalizeNumericInput((right as NumericAnswer).value ?? "")
      );
  }
}

export function unansweredForQuestion(question: Question): Answer {
  switch (question.type) {
    case "SINGLE_CHOICE":
      return { type: "SINGLE_CHOICE", optionLabel: null };
    case "MULTIPLE_CHOICE":
      return { type: "MULTIPLE_CHOICE", optionLabels: [] };
    case "TRUE_FALSE_MATRIX":
      return {
        type: "TRUE_FALSE_MATRIX",
        judgments: { A: null, B: null, C: null, D: null },
      };
    case "NUMERIC_FILL":
      return { type: "NUMERIC_FILL", value: null };
  }
}

export function newIdempotencyKey(): string {
  const bytes = new Uint8Array(32);
  globalThis.crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
}
