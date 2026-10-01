export const QUIZ_QUESTION_TYPES = [
  "SINGLE_CHOICE",
  "MULTIPLE_CHOICE",
  "TRUE_FALSE_MATRIX",
  "NUMERIC_FILL",
] as const;

export type QuizQuestionType = (typeof QUIZ_QUESTION_TYPES)[number];
export type QuizOptionLabel = "A" | "B" | "C" | "D";

export interface QuizMarkdownDiagnostic {
  readonly code: string;
  readonly column: number;
  readonly line: number;
  readonly message: string;
  readonly questionNumber: number | null;
}

export interface QuizPreviewOption {
  readonly content: string;
  readonly correct: boolean;
  readonly label: QuizOptionLabel;
}

export interface QuizPreviewQuestion {
  readonly explanation: string | null;
  readonly number: number;
  readonly numericAnswer: string | null;
  readonly options: readonly QuizPreviewOption[];
  readonly stem: string;
  readonly type: QuizQuestionType | string;
}

export interface QuizMarkdownAnalysis {
  readonly diagnostics: readonly QuizMarkdownDiagnostic[];
  readonly questions: readonly QuizPreviewQuestion[];
}

export interface QuizAutocompleteSuggestion {
  readonly id: string;
  readonly insertText: string;
  readonly label: string;
  readonly replaceEnd: number;
  readonly replaceStart: number;
}

interface MutableOption {
  content: string[];
  correct: boolean;
  label: QuizOptionLabel;
  line: number;
}

interface MutableQuestion {
  answerCount: number;
  answerLine: number | null;
  answerRaw: string | null;
  explanation: string[];
  explanationLine: number | null;
  explanationSeen: boolean;
  headerLine: number;
  number: number;
  options: MutableOption[];
  stem: string[];
  type: string;
  answerCompleteAtExplanation: boolean;
}

type ContentTarget =
  "none" | "stem" | "option" | "after-answer" | "explanation";

const QUESTION_HEADER = /^Câu ([0-9]+) \[([^\]]+)]:(.*)$/;
const QUESTION_HEADER_LIKE = /^Câu [0-9]+(?: \[.*)?$/;
const OPTION = /^(\*)?([A-D])\.(.*)$/;
const ANSWER_PREFIX = "Đáp án:";
const EXPLANATION_PREFIX = "Lời giải:";
const OPTION_LABELS: readonly QuizOptionLabel[] = ["A", "B", "C", "D"];

function diagnostic(
  code: string,
  questionNumber: number | null,
  line: number,
  column: number,
  message: string,
): QuizMarkdownDiagnostic {
  return { code, questionNumber, line, column, message };
}

function sourceLines(source: string): string[] {
  return source.split(/\r\n|\r|\n/);
}

function openingFenceLength(line: string): number | null {
  if (!line.startsWith("```")) {
    return null;
  }
  let count = 0;
  while (count < line.length && line[count] === "`") {
    count += 1;
  }
  if (count < 3 || line.slice(count).includes("`")) {
    return null;
  }
  return count;
}

function isFenceClosing(line: string, minimum: number): boolean {
  if (!line.startsWith("`") || line.length < minimum) {
    return false;
  }
  let count = 0;
  while (count < line.length && line[count] === "`") {
    count += 1;
  }
  return count >= minimum && line.slice(count).trim().length === 0;
}

function appendContent(
  question: MutableQuestion | null,
  target: ContentTarget,
  line: string,
  diagnostics: QuizMarkdownDiagnostic[],
  lineNumber: number,
) {
  if (question === null) {
    diagnostics.push(
      diagnostic(
        "CONTENT_OUTSIDE_QUESTION",
        null,
        lineNumber,
        1,
        "Content appears outside a question",
      ),
    );
    return;
  }

  if (target === "stem") {
    question.stem.push(line);
  } else if (target === "option") {
    question.options.at(-1)?.content.push(line);
  } else if (target === "explanation") {
    question.explanation.push(line);
  } else {
    diagnostics.push(
      diagnostic(
        "UNEXPECTED_CONTENT",
        question.number,
        lineNumber,
        1,
        "Content is not valid at this structural position",
      ),
    );
  }
}

function isAcceptedType(type: string): type is QuizQuestionType {
  return (QUIZ_QUESTION_TYPES as readonly string[]).includes(type);
}

export function isValidNumericAnswerToken(raw: string): boolean {
  const token = raw.trim();
  if (token.length !== 4 || !/[0-9]/.test(token)) {
    return false;
  }
  if (!/^[0-9.-]+$/.test(token)) {
    return false;
  }
  const minus = token.indexOf("-");
  if (minus > 0 || (minus === 0 && token.lastIndexOf("-") !== 0)) {
    return false;
  }
  const dot = token.indexOf(".");
  if (dot === 0 || dot === token.length - 1) {
    return false;
  }
  if (dot >= 0 && token.lastIndexOf(".") !== dot) {
    return false;
  }
  return true;
}

function orderedOptionsComplete(options: readonly MutableOption[]): boolean {
  return (
    options.length === OPTION_LABELS.length &&
    options.every((option, index) => option.label === OPTION_LABELS[index])
  );
}

export function analyzeQuizMarkdown(source: string): QuizMarkdownAnalysis {
  const diagnostics: QuizMarkdownDiagnostic[] = [];
  const questions: MutableQuestion[] = [];
  let current: MutableQuestion | null = null;
  let target: ContentTarget = "none";
  let fence: { ticks: number; line: number } | null = null;

  sourceLines(source).forEach((line, index) => {
    const lineNumber = index + 1;
    if (fence !== null) {
      appendContent(current, target, line, diagnostics, lineNumber);
      if (isFenceClosing(line, fence.ticks)) {
        fence = null;
      }
      return;
    }

    const fenceLength = openingFenceLength(line);
    if (fenceLength !== null) {
      if (
        current === null ||
        !["stem", "option", "explanation"].includes(target)
      ) {
        diagnostics.push(
          diagnostic(
            "UNEXPECTED_CONTENT",
            current?.number ?? null,
            lineNumber,
            1,
            "A fenced code block is not valid at this structural position",
          ),
        );
      } else {
        appendContent(current, target, line, diagnostics, lineNumber);
        fence = { ticks: fenceLength, line: lineNumber };
      }
      return;
    }

    const header = QUESTION_HEADER.exec(line);
    if (header) {
      current = {
        answerCompleteAtExplanation: false,
        answerCount: 0,
        answerLine: null,
        answerRaw: null,
        explanation: [],
        explanationLine: null,
        explanationSeen: false,
        headerLine: lineNumber,
        number: Number.parseInt(header[1], 10),
        options: [],
        stem: [header[3].startsWith(" ") ? header[3].slice(1) : header[3]],
        type: header[2],
      };
      questions.push(current);
      target = "stem";
      return;
    }

    if (QUESTION_HEADER_LIKE.test(line)) {
      const numberMatch = /^Câu ([0-9]+)/.exec(line);
      diagnostics.push(
        diagnostic(
          "MALFORMED_QUESTION_HEADER",
          numberMatch ? Number.parseInt(numberMatch[1], 10) : null,
          lineNumber,
          1,
          "Question header must match 'Câu <number> [<TYPE>]:'",
        ),
      );
      current = null;
      target = "none";
      return;
    }

    const option = OPTION.exec(line);
    if (option) {
      if (current === null) {
        diagnostics.push(
          diagnostic(
            "OPTION_OUTSIDE_QUESTION",
            null,
            lineNumber,
            1,
            "Option marker appears before a question",
          ),
        );
        return;
      }
      if (current.explanationSeen) {
        diagnostics.push(
          diagnostic(
            "OPTION_AFTER_EXPLANATION",
            current.number,
            lineNumber,
            1,
            "Options cannot appear after Lời giải:",
          ),
        );
      }
      current.options.push({
        content: [option[3].startsWith(" ") ? option[3].slice(1) : option[3]],
        correct: Boolean(option[1]),
        label: option[2] as QuizOptionLabel,
        line: lineNumber,
      });
      target = "option";
      return;
    }

    if (line.startsWith(ANSWER_PREFIX)) {
      if (current === null) {
        diagnostics.push(
          diagnostic(
            "ANSWER_OUTSIDE_QUESTION",
            null,
            lineNumber,
            1,
            "Đáp án: appears before a question",
          ),
        );
        return;
      }
      current.answerCount += 1;
      current.answerLine = lineNumber;
      current.answerRaw = line.slice(ANSWER_PREFIX.length);
      if (current.answerCount > 1) {
        diagnostics.push(
          diagnostic(
            "DUPLICATE_NUMERIC_ANSWER",
            current.number,
            lineNumber,
            1,
            "A question may contain only one Đáp án: marker",
          ),
        );
      }
      if (current.explanationSeen) {
        diagnostics.push(
          diagnostic(
            "ANSWER_AFTER_EXPLANATION",
            current.number,
            lineNumber,
            1,
            "Đáp án: cannot appear after Lời giải:",
          ),
        );
      }
      target = "after-answer";
      return;
    }

    if (line.startsWith(EXPLANATION_PREFIX)) {
      if (current === null) {
        diagnostics.push(
          diagnostic(
            "EXPLANATION_OUTSIDE_QUESTION",
            null,
            lineNumber,
            1,
            "Lời giải: appears before a question",
          ),
        );
        return;
      }
      if (current.explanationSeen) {
        diagnostics.push(
          diagnostic(
            "DUPLICATE_EXPLANATION",
            current.number,
            lineNumber,
            1,
            "A question may contain at most one Lời giải: block",
          ),
        );
        return;
      }
      current.explanationSeen = true;
      current.explanationLine = lineNumber;
      current.answerCompleteAtExplanation = isAcceptedType(current.type)
        ? current.type === "NUMERIC_FILL"
          ? current.answerCount === 1 &&
            (current.answerRaw?.trim().length ?? 0) > 0
          : orderedOptionsComplete(current.options)
        : false;
      const suffix = line.slice(EXPLANATION_PREFIX.length);
      current.explanation.push(
        suffix.startsWith(" ") ? suffix.slice(1) : suffix,
      );
      target = "explanation";
      return;
    }

    if (current === null) {
      if (line.trim().length > 0) {
        diagnostics.push(
          diagnostic(
            "CONTENT_OUTSIDE_QUESTION",
            null,
            lineNumber,
            1,
            "Content appears before the first valid question header",
          ),
        );
      }
      return;
    }

    if (target === "after-answer") {
      if (line.trim().length > 0) {
        diagnostics.push(
          diagnostic(
            "CONTENT_AFTER_NUMERIC_ANSWER",
            current.number,
            lineNumber,
            1,
            "Only Lời giải: or the next question may follow a NUMERIC_FILL answer",
          ),
        );
      }
      return;
    }
    appendContent(current, target, line, diagnostics, lineNumber);
  });

  const unclosedFence = fence as { ticks: number; line: number } | null;
  if (unclosedFence !== null) {
    diagnostics.push(
      diagnostic(
        "UNCLOSED_CODE_FENCE",
        questions.at(-1)?.number ?? null,
        unclosedFence.line,
        1,
        "Backtick fenced code block is not closed",
      ),
    );
  }

  if (questions.length === 0) {
    diagnostics.push(
      diagnostic(
        "NO_QUESTIONS",
        null,
        1,
        1,
        "Quiz Markdown must contain at least one question",
      ),
    );
  }

  const seenNumbers = new Set<number>();
  questions.forEach((question, index) => {
    const expected = index + 1;
    if (index === 0 && question.number !== 1) {
      diagnostics.push(
        diagnostic(
          "QUESTION_NUMBER_MUST_START_AT_ONE",
          question.number,
          question.headerLine,
          5,
          "Question numbering must begin at 1",
        ),
      );
    }
    if (seenNumbers.has(question.number)) {
      diagnostics.push(
        diagnostic(
          "DUPLICATE_QUESTION_NUMBER",
          question.number,
          question.headerLine,
          5,
          `Question number ${question.number} is duplicated`,
        ),
      );
    } else if (
      question.number !== expected &&
      !(index === 0 && question.number !== 1)
    ) {
      diagnostics.push(
        diagnostic(
          question.number > expected
            ? "QUESTION_NUMBER_GAP"
            : "QUESTION_NUMBER_NOT_CONTINUOUS",
          question.number,
          question.headerLine,
          5,
          `Expected question number ${expected} but found ${question.number}`,
        ),
      );
    }
    seenNumbers.add(question.number);

    if (!isAcceptedType(question.type)) {
      diagnostics.push(
        diagnostic(
          "UNKNOWN_QUESTION_TYPE",
          question.number,
          question.headerLine,
          1,
          `Unknown or incorrectly cased question type: ${question.type}`,
        ),
      );
      return;
    }

    if (question.stem.join("\n").trim().length === 0) {
      diagnostics.push(
        diagnostic(
          "EMPTY_STEM",
          question.number,
          question.headerLine,
          1,
          "Question stem must not be blank",
        ),
      );
    }

    if (question.type === "NUMERIC_FILL") {
      if (question.options.length > 0) {
        diagnostics.push(
          diagnostic(
            "UNEXPECTED_OPTION",
            question.number,
            question.options[0].line,
            1,
            "NUMERIC_FILL does not use A-D options",
          ),
        );
      }
      if (question.answerCount === 0 || question.answerRaw === null) {
        diagnostics.push(
          diagnostic(
            "NUMERIC_ANSWER_REQUIRED",
            question.number,
            question.headerLine,
            1,
            "NUMERIC_FILL requires Đáp án: <token> on the same line",
          ),
        );
      } else if (!isValidNumericAnswerToken(question.answerRaw)) {
        diagnostics.push(
          diagnostic(
            "NUMERIC_ANSWER_INVALID",
            question.number,
            question.answerLine ?? question.headerLine,
            1,
            "Numeric answer must be exactly four ASCII characters and follow the accepted '-' and '.' rules",
          ),
        );
      }
    } else {
      const seenLabels = new Set<QuizOptionLabel>();
      question.options.forEach((option, optionIndex) => {
        if (seenLabels.has(option.label)) {
          diagnostics.push(
            diagnostic(
              "DUPLICATE_OPTION",
              question.number,
              option.line,
              1,
              `Option ${option.label} is duplicated`,
            ),
          );
        }
        seenLabels.add(option.label);
        const expectedLabel = OPTION_LABELS[optionIndex];
        if (expectedLabel === undefined) {
          diagnostics.push(
            diagnostic(
              "TOO_MANY_OPTIONS",
              question.number,
              option.line,
              1,
              "Choice and matrix questions require exactly A-D",
            ),
          );
        } else if (expectedLabel !== option.label) {
          diagnostics.push(
            diagnostic(
              option.label > expectedLabel
                ? "MISSING_OPTION"
                : "OPTION_OUT_OF_ORDER",
              question.number,
              option.line,
              1,
              `Expected option ${expectedLabel} but found ${option.label}`,
            ),
          );
        }
        if (option.content.join("\n").trim().length === 0) {
          diagnostics.push(
            diagnostic(
              "EMPTY_OPTION",
              question.number,
              option.line,
              1,
              `Option ${option.label} must not be blank`,
            ),
          );
        }
      });
      OPTION_LABELS.forEach((label) => {
        if (!seenLabels.has(label)) {
          diagnostics.push(
            diagnostic(
              "MISSING_OPTION",
              question.number,
              question.headerLine,
              1,
              `Missing required option ${label}`,
            ),
          );
        }
      });
      const correctCount = question.options.filter(
        (option) => option.correct,
      ).length;
      if (question.type === "SINGLE_CHOICE" && correctCount !== 1) {
        diagnostics.push(
          diagnostic(
            "SINGLE_CHOICE_CORRECT_COUNT",
            question.number,
            question.headerLine,
            1,
            "SINGLE_CHOICE requires exactly one correct option",
          ),
        );
      }
      if (question.type === "MULTIPLE_CHOICE" && correctCount < 1) {
        diagnostics.push(
          diagnostic(
            "MULTIPLE_CHOICE_CORRECT_REQUIRED",
            question.number,
            question.headerLine,
            1,
            "MULTIPLE_CHOICE requires at least one correct option",
          ),
        );
      }
      if (question.answerCount > 0) {
        diagnostics.push(
          diagnostic(
            "UNEXPECTED_NUMERIC_ANSWER",
            question.number,
            question.answerLine ?? question.headerLine,
            1,
            "Đáp án: is valid only for NUMERIC_FILL",
          ),
        );
      }
    }

    if (question.explanationSeen && !question.answerCompleteAtExplanation) {
      diagnostics.push(
        diagnostic(
          "EXPLANATION_BEFORE_ANSWER_STRUCTURE",
          question.number,
          question.explanationLine ?? question.headerLine,
          1,
          "Lời giải: may appear only after the complete answer structure",
        ),
      );
    }
  });

  return {
    diagnostics,
    questions: questions.map((question) => ({
      explanation: question.explanationSeen
        ? question.explanation.join("\n")
        : null,
      number: question.number,
      numericAnswer: question.answerRaw?.trim() ?? null,
      options: question.options.map((option) => ({
        content: option.content.join("\n"),
        correct: option.correct,
        label: option.label,
      })),
      stem: question.stem.join("\n"),
      type: question.type,
    })),
  };
}

interface EditorQuestionState {
  answerPresent: boolean;
  answerTextPresent: boolean;
  explanationSeen: boolean;
  options: { correct: boolean; label: QuizOptionLabel }[];
  stemHasContent: boolean;
  type: QuizQuestionType;
}

interface EditorState {
  current: EditorQuestionState | null;
  questionCount: number;
}

function scanEditorState(sourceBeforeCurrentLine: string): EditorState {
  let current: EditorQuestionState | null = null;
  let questionCount = 0;
  let target: ContentTarget = "none";
  let fenceTicks: number | null = null;

  for (const line of sourceLines(sourceBeforeCurrentLine)) {
    if (fenceTicks !== null) {
      if (isFenceClosing(line, fenceTicks)) {
        fenceTicks = null;
      }
      continue;
    }
    const opening = openingFenceLength(line);
    if (opening !== null) {
      if (["stem", "option", "explanation"].includes(target)) {
        fenceTicks = opening;
      }
      continue;
    }
    const header = QUESTION_HEADER.exec(line);
    if (header && isAcceptedType(header[2])) {
      questionCount += 1;
      current = {
        answerPresent: false,
        answerTextPresent: false,
        explanationSeen: false,
        options: [],
        stemHasContent: header[3].trim().length > 0,
        type: header[2],
      };
      target = "stem";
      continue;
    }
    if (current === null) {
      continue;
    }
    const option = OPTION.exec(line);
    if (option) {
      current.options.push({
        correct: Boolean(option[1]),
        label: option[2] as QuizOptionLabel,
      });
      target = "option";
      continue;
    }
    if (line.startsWith(ANSWER_PREFIX)) {
      current.answerPresent = true;
      current.answerTextPresent =
        line.slice(ANSWER_PREFIX.length).trim().length > 0;
      target = "after-answer";
      continue;
    }
    if (line.startsWith(EXPLANATION_PREFIX)) {
      current.explanationSeen = true;
      target = "explanation";
      continue;
    }
    if (target === "stem" && line.trim().length > 0) {
      current.stemHasContent = true;
    }
  }
  return { current, questionCount };
}

function isInsideFenceBeforeLine(sourceBeforeCurrentLine: string): boolean {
  let fenceTicks: number | null = null;
  for (const line of sourceLines(sourceBeforeCurrentLine)) {
    if (fenceTicks !== null) {
      if (isFenceClosing(line, fenceTicks)) {
        fenceTicks = null;
      }
      continue;
    }
    fenceTicks = openingFenceLength(line);
  }
  return fenceTicks !== null;
}

export function getQuizAutocompleteSuggestions(
  source: string,
  caret: number,
): readonly QuizAutocompleteSuggestion[] {
  const safeCaret = Math.max(0, Math.min(caret, source.length));
  const lineStart = source.lastIndexOf("\n", safeCaret - 1) + 1;
  const linePrefix = source.slice(lineStart, safeCaret).replace(/^\r/, "");
  const beforeLine = source.slice(0, lineStart);

  if (/^[\t ]/.test(linePrefix) || isInsideFenceBeforeLine(beforeLine)) {
    return [];
  }

  const state = scanEditorState(beforeLine);
  const range = { replaceStart: lineStart, replaceEnd: safeCaret };

  if (["C", "Câ", "Câu"].includes(linePrefix)) {
    const next = state.questionCount + 1;
    return QUIZ_QUESTION_TYPES.map((type) => {
      const insertText = `Câu ${next} [${type}]: `;
      return {
        id: `question-${type}`,
        insertText,
        label: insertText.trimEnd(),
        ...range,
      };
    });
  }

  if (/^Câu [0-9]+ \[$/.test(linePrefix)) {
    return QUIZ_QUESTION_TYPES.map((type) => ({
      id: `type-${type}`,
      insertText: `${linePrefix}${type}]: `,
      label: type,
      ...range,
    }));
  }

  const current = state.current;
  if (current === null || current.explanationSeen) {
    return [];
  }

  if (current.type === "NUMERIC_FILL") {
    if (!current.answerPresent && ["Đ", "Đá", "Đáp"].includes(linePrefix)) {
      return [
        {
          id: "numeric-answer",
          insertText: "Đáp án: ",
          label: "Đáp án:",
          ...range,
        },
      ];
    }
    if (
      current.answerPresent &&
      current.answerTextPresent &&
      ["L", "Lờ", "Lời"].includes(linePrefix)
    ) {
      return [
        {
          id: "explanation",
          insertText: "Lời giải: ",
          label: "Lời giải:",
          ...range,
        },
      ];
    }
    return [];
  }

  if (current.options.length < OPTION_LABELS.length && current.stemHasContent) {
    const expected = OPTION_LABELS[current.options.length];
    const completedInOrder = current.options.every(
      (option, index) => option.label === OPTION_LABELS[index],
    );
    if (
      completedInOrder &&
      ["", expected, `*${expected}`].includes(linePrefix)
    ) {
      return [
        {
          id: `option-${expected}`,
          insertText: `${expected}. `,
          label: `${expected}.`,
          ...range,
        },
        {
          id: `option-${expected}-correct`,
          insertText: `*${expected}. `,
          label: `*${expected}.`,
          ...range,
        },
      ];
    }
  }

  if (
    orderedOptionsComplete(
      current.options.map((option) => ({ ...option, content: [], line: 0 })),
    ) &&
    ["L", "Lờ", "Lời"].includes(linePrefix)
  ) {
    return [
      {
        id: "explanation",
        insertText: "Lời giải: ",
        label: "Lời giải:",
        ...range,
      },
    ];
  }

  return [];
}
