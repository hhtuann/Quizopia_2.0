import { describe, expect, it } from "vitest";
import {
  QUIZ_QUESTION_TYPES,
  analyzeQuizMarkdown,
  getQuizAutocompleteSuggestions,
  isValidNumericAnswerToken,
  toggleQuizOptionCorrectness,
} from "./quiz-markdown";

function suggestions(source: string) {
  return getQuizAutocompleteSuggestions(source, source.length);
}

describe("Quiz Markdown autocomplete context", () => {
  it.each(["C", "Câ", "Câu"])(
    "offers four direct question snippets for %s at column 1",
    (prefix) => {
      const result = suggestions(prefix);
      expect(result).toHaveLength(4);
      expect(result.map((item) => item.label)).toEqual(
        QUIZ_QUESTION_TYPES.map((type) => `Câu 1 [${type}]:`),
      );
    },
  );

  it.each(["c", "câ", "câu"])(
    "accepts lowercase question prefix %s and inserts canonical syntax",
    (prefix) => {
      const result = suggestions(prefix);
      expect(result).toHaveLength(4);
      expect(result[0]?.insertText).toBe("Câu 1 [SINGLE_CHOICE]: ");
    },
  );

  it("suggests the expected next question number as assistance", () => {
    const result = suggestions("Câu 1 [NUMERIC_FILL]: Value?\nĐáp án: 1234\nC");
    expect(result.map((item) => item.label)).toEqual(
      QUIZ_QUESTION_TYPES.map((type) => `Câu 2 [${type}]:`),
    );
  });

  it("offers exactly four type fallbacks after a manual opening bracket", () => {
    const result = suggestions("Câu 3 [");
    expect(result).toHaveLength(4);
    expect(result.map((item) => item.label)).toEqual(QUIZ_QUESTION_TYPES);
    expect(result[3]?.insertText).toBe("Câu 3 [NUMERIC_FILL]: ");
  });

  it.each([
    "Đâu là câu trả lời đúng?",
    "Đây là câu ở giữa văn bản",
    " C",
    "  Câu",
  ])(
    "does not trigger structural completion for nonstructural text: %s",
    (source) => {
      expect(suggestions(source)).toEqual([]);
    },
  );

  it("does not offer structural completion inside a fenced code block", () => {
    expect(suggestions("Câu 1 [SINGLE_CHOICE]: code\n```text\nC")).toEqual([]);
    expect(
      suggestions("Câu 1 [SINGLE_CHOICE]: code\n```text\n*A. fake\nC"),
    ).toEqual([]);
  });

  it("advances option completion from A through D and preserves multiline option context", () => {
    const header = "Câu 1 [MULTIPLE_CHOICE]: Chọn đáp án\n";
    expect(suggestions(header).map((item) => item.label)).toEqual([
      "A.",
      "*A.",
    ]);
    expect(
      suggestions(`${header}A. first line\nsecond line\n`).map(
        (item) => item.label,
      ),
    ).toEqual(["B.", "*B."]);
    expect(
      suggestions(`${header}A. a\nB. b\nC. c\n`).map((item) => item.label),
    ).toEqual(["D.", "*D."]);
  });

  it("offers numeric answer completion only in NUMERIC_FILL context", () => {
    expect(
      suggestions("Câu 1 [NUMERIC_FILL]: value?\nĐáp").map(
        (item) => item.label,
      ),
    ).toEqual(["Đáp án:"]);
    expect(suggestions("Câu 1 [SINGLE_CHOICE]: choose\nĐáp")).toEqual([]);
    for (const prefix of ["đ", "đá", "đáp"]) {
      expect(
        suggestions(`Câu 1 [NUMERIC_FILL]: value?\n${prefix}`)[0]?.insertText,
      ).toBe("Đáp án: ");
    }
  });

  it("offers explanation only after the required answer structure and never twice", () => {
    const incomplete = "Câu 1 [SINGLE_CHOICE]: choose\n*A. a\nB. b\nC. c\nL";
    expect(suggestions(incomplete)).toEqual([]);

    const complete =
      "Câu 1 [SINGLE_CHOICE]: choose\n*A. a\nB. b\nC. c\nD. d\nL";
    expect(suggestions(complete).map((item) => item.label)).toEqual([
      "Lời giải:",
    ]);

    const numeric = "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50\nLời";
    expect(suggestions(numeric).map((item) => item.label)).toEqual([
      "Lời giải:",
    ]);

    expect(suggestions(`${complete.slice(0, -1)}Lời giải: done\nL`)).toEqual(
      [],
    );
    for (const prefix of ["l", "lờ", "lời"]) {
      expect(
        suggestions(`${complete.slice(0, -1)}${prefix}`)[0]?.insertText,
      ).toBe("Lời giải: ");
    }
  });
});

describe("Quiz Markdown frontend analysis", () => {
  it.each([
    "1234",
    "0001",
    "2.50",
    "0.25",
    "-3.5",
    "-0.5",
    "12.3",
    "-123",
    "-.50",
  ])("accepts numeric token %s", (token) =>
    expect(isValidNumericAnswerToken(token)).toBe(true),
  );

  it.each([
    "123",
    "12345",
    "+123",
    "1,25",
    ".250",
    "250.",
    "1..2",
    "--12",
    "1 23",
    "１２３４",
    "1e03",
    "12-3",
    "-1.23",
  ])("rejects numeric token %s", (token) =>
    expect(isValidNumericAnswerToken(token)).toBe(false),
  );

  it("preserves multiline stems, options and explanations in preview data", () => {
    const result = analyzeQuizMarkdown(
      "Câu 1 [SINGLE_CHOICE]: line one\nline two\n*A. first\ncontinued\nB. b\nC. c\nD. d\nLời giải: why\nmore detail",
    );
    expect(result.questions[0]).toMatchObject({
      stem: "line one\nline two",
      explanation: "why\nmore detail",
    });
    expect(result.questions[0]?.options[0]).toMatchObject({
      label: "A",
      content: "first\ncontinued",
      correct: true,
    });
    expect(result.diagnostics).toEqual([]);
  });

  it("maps question and option offsets correctly across CRLF and multiline fenced content", () => {
    const source =
      "Câu 1 [SINGLE_CHOICE]: inspect\r\n```text\r\nCâu 99 [NUMERIC_FILL]: fake\r\n```\r\n*A. real\r\nB. b\r\nC. c\r\nD. d\r\n\r\nCâu 2 [NUMERIC_FILL]: value\r\nĐáp án: 2.50";
    const result = analyzeQuizMarkdown(source);

    expect(result.questions[0]?.source).toEqual({ line: 1, offset: 0 });
    expect(result.questions[0]?.options[0]?.source).toEqual({
      line: 5,
      markerOffset: source.indexOf("A. real"),
      starOffset: source.indexOf("*A. real"),
    });
    expect(result.questions[1]?.source).toEqual({
      line: 10,
      offset: source.indexOf("Câu 2"),
    });
  });

  it("moves a SINGLE_CHOICE marker without normalizing unrelated source", () => {
    const source =
      "Câu 1 [SINGLE_CHOICE]: stem\r\n*A. first\r\ncontinued\r\nB. second\r\nC. third\r\nD. fourth\r\n\r\nLời giải: keep  two spaces";
    const question = analyzeQuizMarkdown(source).questions[0]!;
    const next = toggleQuizOptionCorrectness(
      source,
      question,
      question.options[1]!,
    );

    expect(next).toBe(
      "Câu 1 [SINGLE_CHOICE]: stem\r\nA. first\r\ncontinued\r\n*B. second\r\nC. third\r\nD. fourth\r\n\r\nLời giải: keep  two spaces",
    );
  });

  it.each([
    ["MULTIPLE_CHOICE", "*B. second"],
    ["TRUE_FALSE_MATRIX", "*B. second"],
  ])("toggles only the selected %s marker", (type, expectedLine) => {
    const source = `Câu 1 [${type}]: stem\n*A. first\nB. second\nC. third\nD. fourth`;
    const question = analyzeQuizMarkdown(source).questions[0]!;
    const next = toggleQuizOptionCorrectness(
      source,
      question,
      question.options[1]!,
    );

    expect(next.split("\n")[2]).toBe(expectedLine);
    expect(next.replace("*B. second", "B. second")).toBe(source);
  });

  it("supports TRUE_FALSE_MATRIX and NUMERIC_FILL preview structures", () => {
    const result = analyzeQuizMarkdown(
      "Câu 1 [TRUE_FALSE_MATRIX]: matrix\n*A. true\nB. false\n*C. true\nD. false\nCâu 2 [NUMERIC_FILL]: value\nĐáp án: 2.50",
    );
    expect(result.diagnostics).toEqual([]);
    expect(result.questions[0]?.type).toBe("TRUE_FALSE_MATRIX");
    expect(
      result.questions[0]?.options
        .filter((option) => option.correct)
        .map((option) => option.label),
    ).toEqual(["A", "C"]);
    expect(result.questions[1]?.numericAnswer).toBe("2.50");
  });

  it("keeps structural-looking content inside fences as content", () => {
    const result = analyzeQuizMarkdown(
      "Câu 1 [SINGLE_CHOICE]: inspect\n```text\nCâu 99 [NUMERIC_FILL]: fake\n*A. fake\nĐáp án: fake\nLời giải: fake\n```\n*A. real\nB. b\nC. c\nD. d",
    );
    expect(result.questions).toHaveLength(1);
    expect(result.questions[0]?.number).toBe(1);
    expect(result.questions[0]?.stem).toContain("Câu 99 [NUMERIC_FILL]: fake");
    expect(result.diagnostics).toEqual([]);
  });

  it("reports malformed source and unclosed fences instead of silently repairing it", () => {
    expect(
      analyzeQuizMarkdown("Câu 1 [SINGLE_CHOICE]").diagnostics.map(
        (item) => item.code,
      ),
    ).toContain("MALFORMED_QUESTION_HEADER");
    expect(
      analyzeQuizMarkdown(
        "Câu 1 [SINGLE_CHOICE]: stem\n```java\nint x = 1;",
      ).diagnostics.map((item) => item.code),
    ).toContain("UNCLOSED_CODE_FENCE");
  });
});
