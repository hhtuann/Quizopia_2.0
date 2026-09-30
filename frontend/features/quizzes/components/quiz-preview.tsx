"use client";

import { Fragment, type ReactNode } from "react";
import type { QuizMarkdownServerError } from "../api/quiz-api-client";
import {
  analyzeQuizMarkdown,
  type QuizMarkdownDiagnostic,
  type QuizPreviewQuestion,
} from "../model/quiz-markdown";

interface QuizPreviewProps {
  readonly onDiagnosticSelect?: (line: number, column: number) => void;
  readonly serverDiagnostics?: readonly QuizMarkdownServerError[];
  readonly source: string;
}

function renderInline(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  const pattern = /(`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*)/g;
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
    const opening = fenceOpening(line);
    if (opening !== null) {
      flushParagraph();
      fenceTicks = opening;
      code = [];
    } else if (line.trim().length === 0) {
      flushParagraph();
    } else {
      paragraph.push(line);
    }
  });
  flushParagraph();
  flushCode();

  return <div className="space-y-3">{blocks}</div>;
}

function PreviewQuestion({
  question,
}: {
  readonly question: QuizPreviewQuestion;
}) {
  return (
    <article className="rounded-xl border border-border bg-surface p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-base font-semibold text-foreground">
          Câu {question.number}
        </h3>
        <span className="rounded-md bg-surface-muted px-2 py-1 text-xs font-semibold text-foreground-secondary">
          {question.type}
        </span>
      </div>
      <div className="mt-4 text-sm text-foreground-secondary">
        <MarkdownContent value={question.stem} />
      </div>
      {question.options.length > 0 ? (
        <ol
          className="mt-5 space-y-2"
          aria-label={`Options for question ${question.number}`}
        >
          {question.options.map((option) => (
            <li
              className="rounded-lg border border-border bg-surface-muted p-3"
              key={`${question.number}-${option.label}`}
            >
              <div className="flex gap-3">
                <span className="font-semibold text-foreground">
                  {option.label}.
                </span>
                <div className="min-w-0 flex-1 text-sm text-foreground-secondary">
                  <MarkdownContent value={option.content} />
                </div>
                {option.correct ? (
                  <span
                    className="text-xs font-semibold text-success"
                    aria-label="Correct answer"
                  >
                    Correct
                  </span>
                ) : null}
              </div>
            </li>
          ))}
        </ol>
      ) : null}
      {question.numericAnswer !== null ? (
        <p className="mt-5 rounded-lg border border-border bg-surface-muted p-3 text-sm text-foreground-secondary">
          <span className="font-semibold text-foreground">Đáp án:</span>{" "}
          <code className="font-mono">{question.numericAnswer}</code>
        </p>
      ) : null}
      {question.explanation !== null ? (
        <div className="mt-5 border-t border-border pt-4">
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
  onDiagnosticSelect,
  serverDiagnostics = [],
  source,
}: QuizPreviewProps) {
  const analysis = analyzeQuizMarkdown(source);

  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-lg font-semibold text-foreground">Live preview</h2>
        <p className="mt-1 text-sm leading-6 text-foreground-muted">
          Safe preview of the accepted Markdown subset. Frontend diagnostics are
          authoring guidance; publish validation is authoritative.
        </p>
      </div>

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
              question={question}
            />
          ))}
        </div>
      )}
    </div>
  );
}
