"use client";

import { Fragment, type ReactNode, useId } from "react";
import katex from "katex";
import type { QuizMarkdownServerError } from "../api/quiz-api-client";
import {
  analyzeQuizMarkdown,
  type QuizMarkdownDiagnostic,
  type QuizPreviewOption,
  type QuizPreviewQuestion,
} from "../model/quiz-markdown";
import { EditorPaneHeader } from "./editor-pane-header";

interface QuizPreviewProps {
  readonly description?: string;
  readonly onDiagnosticSelect?: (line: number, column: number) => void;
  readonly onOptionToggle?: (
    question: QuizPreviewQuestion,
    option: QuizPreviewOption,
  ) => void;
  readonly onQuestionSelect?: (question: QuizPreviewQuestion) => void;
  readonly readOnly?: boolean;
  readonly serverDiagnostics?: readonly QuizMarkdownServerError[];
  readonly source: string;
  readonly title?: string;
}

function renderInline(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  // Match code before math, and only recognize unescaped, paired dollar signs.
  const pattern =
    /(`[^`\n]+`|(?<!\\)\$(?!\$)[^$\n]+(?<!\\)\$(?!\$)|\*\*[^*\n]+\*\*|\*[^*\n]+\*)/g;
  let cursor = 0;
  let match: RegExpExecArray | null;
  let key = 0;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > cursor) {
      nodes.push(text.slice(cursor, match.index));
    }
    const token = match[0];
    if (token.startsWith("`")) {
      nodes.push(
        <code
          className="rounded bg-surface-strong px-1 py-0.5 font-mono text-[0.92em]"
          key={key++}
        >
          {token.slice(1, -1)}
        </code>,
      );
    } else if (token.startsWith("$")) {
      const contents = token.slice(1, -1);
      // Treat ambiguous currency prose as text, not an accidental math expression.
      const currencyProse = /^\d+(?:[.,]\d{1,2})?\s+(?:and|or)\s*$/i.test(
        contents,
      );
      nodes.push(
        contents.trim() === contents && !currencyProse
          ? renderMath(contents, false, key++)
          : token,
      );
    } else if (token.startsWith("**")) {
      nodes.push(<strong key={key++}>{token.slice(2, -2)}</strong>);
    } else {
      nodes.push(<em key={key++}>{token.slice(1, -1)}</em>);
    }
    cursor = match.index + token.length;
  }
  if (cursor < text.length) {
    nodes.push(text.slice(cursor));
  }
  return nodes;
}

function renderMath(
  source: string,
  displayMode: boolean,
  key: number,
): ReactNode {
  try {
    // KaTeX escapes user input, disables trust-only commands and never executes HTML.
    const html = katex.renderToString(source, {
      displayMode,
      throwOnError: true,
      trust: false,
      strict: "error",
      maxExpand: 100,
      maxSize: 20,
    });
    if (displayMode) {
      return (
        <div
          className="overflow-x-auto py-1"
          dangerouslySetInnerHTML={{ __html: html }}
          key={key}
        />
      );
    }
    return <span dangerouslySetInnerHTML={{ __html: html }} key={key} />;
  } catch {
    // Unknown or malformed math remains visible as literal authored source.
    return displayMode ? `$$${source}$$` : `$${source}$`;
  }
}

function fenceOpening(line: string): number | null {
  if (!line.startsWith("```")) return null;
  let count = 0;
  while (line[count] === "`") count += 1;
  return line.slice(count).includes("`") ? null : count;
}

function fenceClosing(line: string, ticks: number): boolean {
  let count = 0;
  while (line[count] === "`") count += 1;
  return count >= ticks && line.slice(count).trim().length === 0;
}

function MarkdownContent({ value }: { readonly value: string }) {
  const lines = value.split("\n");
  const blocks: ReactNode[] = [];
  let paragraph: string[] = [];
  let code: string[] | null = null;
  let fenceTicks = 0;
  let displayMath: string[] | null = null;

  function flushParagraph() {
    if (paragraph.length === 0) return;
    const current = paragraph;
    blocks.push(
      <p className="leading-7" key={`p-${blocks.length}`}>
        {current.map((line, index) => (
          <Fragment key={`${index}-${line}`}>
            {index > 0 ? <br /> : null}
            {renderInline(line)}
          </Fragment>
        ))}
      </p>,
    );
    paragraph = [];
  }

  function flushCode() {
    if (code === null) return;
    blocks.push(
      <pre
        className="overflow-x-auto rounded-lg border border-border bg-surface-muted p-3 text-sm leading-6"
        key={`code-${blocks.length}`}
      >
        <code className="font-mono">{code.join("\n")}</code>
      </pre>,
    );
    code = null;
    fenceTicks = 0;
  }

  lines.forEach((line) => {
    if (code !== null) {
      if (fenceClosing(line, fenceTicks)) {
        flushCode();
      } else {
        code.push(line);
      }
      return;
    }
    if (displayMath !== null) {
      if (line.trim() === "$$") {
        blocks.push(renderMath(displayMath.join("\n"), true, blocks.length));
        displayMath = null;
      } else {
        displayMath.push(line);
      }
      return;
    }
    const opening = fenceOpening(line);
    if (opening !== null) {
      flushParagraph();
      fenceTicks = opening;
      code = [];
    } else if (line.trim() === "$$") {
      flushParagraph();
      displayMath = [];
    } else if (/^\$\$[^$\n]+\$\$$/.test(line.trim())) {
      flushParagraph();
      blocks.push(renderMath(line.trim().slice(2, -2), true, blocks.length));
    } else if (line.trim().length === 0) {
      flushParagraph();
    } else {
      paragraph.push(line);
    }
  });
  flushParagraph();
  flushCode();
  const unclosedMath = displayMath as string[] | null;
  if (unclosedMath !== null) {
    blocks.push(`$$\n${unclosedMath.join("\n")}`);
  }

  return <div className="space-y-3">{blocks}</div>;
}

function PreviewQuestion({
  onOptionToggle,
  onQuestionSelect,
  question,
  readOnly,
}: {
  readonly onOptionToggle?: (
    question: QuizPreviewQuestion,
    option: QuizPreviewOption,
  ) => void;
  readonly onQuestionSelect?: (question: QuizPreviewQuestion) => void;
  readonly question: QuizPreviewQuestion;
  readonly readOnly: boolean;
}) {
  const canSelectQuestion = !readOnly && onQuestionSelect !== undefined;

  return (
    <article
      className="rounded-xl border border-border bg-surface p-4 sm:p-5"
      data-source-line={question.source.line}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-base font-semibold text-foreground">
          {canSelectQuestion ? (
            <button
              aria-label={`Jump to source for question ${question.number}, line ${question.source.line}`}
              className="rounded-md text-left font-semibold text-foreground transition-colors hover:text-primary focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-focus motion-reduce:transition-none"
              onClick={() => onQuestionSelect(question)}
              type="button"
            >
              Câu {question.number}
            </button>
          ) : (
            `Câu ${question.number}`
          )}
        </h3>
        <div className="flex items-center gap-2">
          <span className="rounded-md bg-surface-muted px-2 py-1 font-mono text-xs font-semibold text-foreground-secondary">
            {question.type}
          </span>
        </div>
      </div>
      <div className="mt-2 text-sm text-foreground-secondary">
        <MarkdownContent value={question.stem} />
      </div>
      {question.options.length > 0 ? (
        <ol
          className="mt-3 space-y-1.5"
          aria-label={`Options for question ${question.number}`}
        >
          {question.options.map((option) => (
            <li key={`${question.number}-${option.label}`}>
              {readOnly || onOptionToggle === undefined ? (
                <div
                  aria-label={`${option.label}. ${option.correct ? "Marked correct" : "Not marked correct"}`}
                  className={`w-full rounded-lg border px-3 py-2.5 text-left ${
                    option.correct
                      ? "border-primary/50 bg-primary/10"
                      : "border-border bg-surface-muted"
                  }`}
                  role="group"
                >
                  <span className="flex gap-3">
                    <span className="font-semibold text-foreground">
                      {option.label}.
                    </span>
                    <div className="min-w-0 flex-1 text-sm text-foreground-secondary">
                      <MarkdownContent value={option.content} />
                    </div>
                    {option.correct ? (
                      <svg
                        aria-hidden="true"
                        className="mt-0.5 size-5 shrink-0 text-primary"
                        fill="none"
                        viewBox="0 0 24 24"
                      >
                        <path
                          d="m5 12 4 4L19 6"
                          stroke="currentColor"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth="2.5"
                        />
                      </svg>
                    ) : null}
                  </span>
                </div>
              ) : (
                <button
                  aria-label={`${option.label}. ${option.correct ? "Marked correct" : "Not marked correct"}`}
                  aria-pressed={option.correct}
                  className={`min-h-11 w-full rounded-lg border px-3 py-2.5 text-left transition-colors focus-visible:ring-2 focus-visible:ring-focus motion-reduce:transition-none ${
                    option.correct
                      ? "border-primary/50 bg-primary/10 hover:bg-primary/15"
                      : "border-border bg-surface-muted hover:border-primary/30 hover:bg-primary/5"
                  }`}
                  onClick={(event) => {
                    event.stopPropagation();
                    onOptionToggle(question, option);
                  }}
                  type="button"
                >
                  <span className="flex gap-3">
                    <span className="font-semibold text-foreground">
                      {option.label}.
                    </span>
                    <div className="min-w-0 flex-1 text-sm text-foreground-secondary">
                      <MarkdownContent value={option.content} />
                    </div>
                    {option.correct ? (
                      <svg
                        aria-hidden="true"
                        className="mt-0.5 size-5 shrink-0 text-primary"
                        fill="none"
                        viewBox="0 0 24 24"
                      >
                        <path
                          d="m5 12 4 4L19 6"
                          stroke="currentColor"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth="2.5"
                        />
                      </svg>
                    ) : null}
                  </span>
                </button>
              )}
            </li>
          ))}
        </ol>
      ) : null}
      {question.numericAnswer !== null ? (
        <p className="mt-3 rounded-lg border border-border bg-surface-muted p-3 text-sm text-foreground-secondary">
          <span className="font-semibold text-foreground">Đáp án:</span>{" "}
          <code className="font-mono">{question.numericAnswer}</code>
        </p>
      ) : null}
      {question.explanation !== null ? (
        <div className="mt-3 border-t border-border pt-3">
          <p className="mb-2 text-sm font-semibold text-foreground">Lời giải</p>
          <div className="text-sm text-foreground-secondary">
            <MarkdownContent value={question.explanation} />
          </div>
        </div>
      ) : null}
    </article>
  );
}

function DiagnosticButton({
  diagnostic,
  onSelect,
}: {
  readonly diagnostic: Pick<
    QuizMarkdownDiagnostic,
    "code" | "column" | "line" | "message"
  >;
  readonly onSelect?: (line: number, column: number) => void;
}) {
  if (onSelect === undefined) {
    return (
      <li className="rounded-lg border border-danger/20 bg-danger/5 px-3 py-2 text-sm text-foreground-secondary">
        <span className="font-semibold text-danger">{diagnostic.code}</span>{" "}
        <span className="text-foreground-muted">
          L{diagnostic.line}:C{diagnostic.column}
        </span>
        <span className="mt-1 block">{diagnostic.message}</span>
      </li>
    );
  }

  return (
    <li>
      <button
        className="w-full rounded-lg border border-danger/20 bg-danger/5 px-3 py-2 text-left text-sm text-foreground-secondary transition-colors hover:bg-danger/10 focus-visible:ring-2 focus-visible:ring-focus motion-reduce:transition-none"
        onClick={() => onSelect?.(diagnostic.line, diagnostic.column)}
        type="button"
      >
        <span className="font-semibold text-danger">{diagnostic.code}</span>{" "}
        <span className="text-foreground-muted">
          L{diagnostic.line}:C{diagnostic.column}
        </span>
        <span className="mt-1 block">{diagnostic.message}</span>
      </button>
    </li>
  );
}

export function QuizPreview({
  description,
  onDiagnosticSelect,
  onOptionToggle,
  onQuestionSelect,
  readOnly = false,
  serverDiagnostics = [],
  source,
  title = "Live preview",
}: QuizPreviewProps) {
  const analysis = analyzeQuizMarkdown(source);
  const generatedId = useId();

  return (
    <div className="space-y-3">
      <EditorPaneHeader
        description={description}
        descriptionId={`${generatedId}-help`}
        title={title}
        titleId={`${generatedId}-title`}
      />

      {serverDiagnostics.length > 0 ? (
        <section aria-labelledby="server-diagnostics-title">
          <h3
            className="text-sm font-semibold text-danger"
            id="server-diagnostics-title"
          >
            Publish validation
          </h3>
          <ul className="mt-2 space-y-2">
            {serverDiagnostics.map((item, index) => (
              <DiagnosticButton
                diagnostic={{
                  code: item.code,
                  column: item.column ?? 1,
                  line: item.line ?? 1,
                  message: item.message,
                }}
                key={`${item.code}-${item.line ?? 0}-${index}`}
                onSelect={onDiagnosticSelect}
              />
            ))}
          </ul>
        </section>
      ) : null}

      {serverDiagnostics.length === 0 && analysis.diagnostics.length > 0 ? (
        <section aria-labelledby="frontend-diagnostics-title">
          <h3
            className="text-sm font-semibold text-warning"
            id="frontend-diagnostics-title"
          >
            Editor diagnostics
          </h3>
          <ul className="mt-2 space-y-2">
            {analysis.diagnostics.slice(0, 12).map((item, index) => (
              <DiagnosticButton
                diagnostic={item}
                key={`${item.code}-${item.line}-${index}`}
                onSelect={onDiagnosticSelect}
              />
            ))}
          </ul>
        </section>
      ) : null}

      {analysis.questions.length === 0 ? (
        <div className="rounded-xl border border-dashed border-border-strong bg-surface-muted p-5 text-sm leading-6 text-foreground-muted">
          Start a question with <code className="font-mono">Câu 1 [TYPE]:</code>{" "}
          to see the quiz preview.
        </div>
      ) : (
        <div className="space-y-4">
          {analysis.questions.map((question, index) => (
            <PreviewQuestion
              key={`${question.number}-${index}`}
              onOptionToggle={onOptionToggle}
              onQuestionSelect={onQuestionSelect}
              question={question}
              readOnly={readOnly}
            />
          ))}
        </div>
      )}
    </div>
  );
}
