import { describe, expect, it } from "vitest";
import type {
  Answer,
  AttemptView,
  Question,
} from "../api/attempt-contract.generated";
import {
  attemptRevisionSchema,
  attemptViewSchema,
  boundedQuestionPage,
  effectiveAttemptDeadline,
  isUnanswered,
  isValidNumericToken,
  normalizeNumericInput,
  sameAnswer,
  unansweredForQuestion,
} from "./attempt";

const answers: readonly Answer[] = [
  { type: "SINGLE_CHOICE", optionLabel: null },
  { type: "MULTIPLE_CHOICE", optionLabels: [] },
  {
    type: "TRUE_FALSE_MATRIX",
    judgments: { A: null, B: null, C: null, D: null },
  },
  { type: "NUMERIC_FILL", value: null },
];

describe("Attempt wire model", () => {
  it("uses the earliest server-issued Attempt or closure boundary", () => {
    expect(
      effectiveAttemptDeadline({
        deadlineAt: "2026-10-16T09:00:00Z",
        closure: {
          closureRequestId: "59d0a376-c51a-4569-99b0-0115f4e839d2",
          confirmedNoticeAt: null,
          closureCutoffAt: "2026-10-16T08:30:00Z",
        },
      }),
    ).toBe("2026-10-16T08:30:00Z");
    expect(
      effectiveAttemptDeadline({
        deadlineAt: "2026-10-16T08:00:00Z",
        closure: {
          closureRequestId: "59d0a376-c51a-4569-99b0-0115f4e839d2",
          confirmedNoticeAt: null,
          closureCutoffAt: "2026-10-16T08:30:00Z",
        },
      }),
    ).toBe("2026-10-16T08:00:00Z");
  });

  it("keeps canonical signed-64-bit revisions as strings without number conversion", () => {
    expect(attemptRevisionSchema.parse("9223372036854775807")).toBe(
      "9223372036854775807",
    );
    expect(
      attemptRevisionSchema.safeParse(9_223_372_036_854_775_807).success,
    ).toBe(false);
    expect(attemptRevisionSchema.safeParse("01").success).toBe(false);
    expect(attemptRevisionSchema.safeParse("9223372036854775808").success).toBe(
      false,
    );
  });

  it.each([
    ["2.50", true],
    ["1234", true],
    ["-2.5", true],
    ["-.25", true],
    ["0001", true],
    ["2.5", false],
    ["1", false],
    ["12345", false],
    ["+2.5", false],
    [".250", false],
    ["1e03", false],
  ])("validates the exact numeric token %s", (value, valid) => {
    expect(isValidNumericToken(value)).toBe(valid);
  });

  it("trims only outer ASCII whitespace and preserves accepted spelling", () => {
    expect(normalizeNumericInput("\t 2.50\r")).toBe("2.50");
    expect(normalizeNumericInput("\t \n")).toBeNull();
    expect(normalizeNumericInput("\u00a02.50\u00a0")).toBe("\u00a02.50\u00a0");
  });

  it("models every question type's explicit unanswered value", () => {
    const questions: Question[] = [
      { number: 1, type: "SINGLE_CHOICE", stemMarkdown: "one", options: [] },
      { number: 2, type: "MULTIPLE_CHOICE", stemMarkdown: "two", options: [] },
      {
        number: 3,
        type: "TRUE_FALSE_MATRIX",
        stemMarkdown: "three",
        options: [],
      },
      { number: 4, type: "NUMERIC_FILL", stemMarkdown: "four", options: [] },
    ];
    questions.forEach((question, index) => {
      expect(unansweredForQuestion(question)).toEqual(answers[index]);
      expect(isUnanswered(answers[index]!)).toBe(true);
    });
  });

  it("compares multi-select order independently but preserves numeric spelling", () => {
    expect(
      sameAnswer(
        { type: "MULTIPLE_CHOICE", optionLabels: ["A", "C"] },
        { type: "MULTIPLE_CHOICE", optionLabels: ["C", "A"] },
      ),
    ).toBe(true);
    expect(
      sameAnswer(
        { type: "NUMERIC_FILL", value: "0001" },
        { type: "NUMERIC_FILL", value: "1.00" },
      ),
    ).toBe(false);
  });

  it("validates the accepted 12,613-question compatibility size without truncation", () => {
    const questions: Question[] = Array.from(
      { length: 12_613 },
      (_, index) => ({
        number: index + 1,
        type: "NUMERIC_FILL" as const,
        stemMarkdown: `Question ${index + 1}`,
        options: [],
      }),
    );
    const attempt: AttemptView = {
      attemptId: "e7b14962-3a2e-4d2d-926a-3b36ea90c199",
      publicationId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
      ordinal: "9223372036854775807",
      state: "ACTIVE",
      startedAt: "2026-10-16T08:00:00Z",
      deadlineAt: null,
      durationMinutes: 0,
      revision: "0",
      serverNow: "2026-10-16T08:00:00Z",
      questions,
      answers: questions.map((question) => ({
        questionNumber: question.number,
        answer: { type: "NUMERIC_FILL", value: null },
      })),
      closure: null,
    };
    const parsed = attemptViewSchema.parse(attempt);
    expect(parsed.questions).toHaveLength(12_613);
    expect(parsed.answers.at(-1)?.questionNumber).toBe(12_613);
    expect(boundedQuestionPage(parsed.questions.length, 0)).toEqual({
      page: 0,
      start: 0,
      end: 100,
    });
    expect(boundedQuestionPage(parsed.questions.length, 126)).toEqual({
      page: 126,
      start: 12_600,
      end: 12_613,
    });
  });
});
