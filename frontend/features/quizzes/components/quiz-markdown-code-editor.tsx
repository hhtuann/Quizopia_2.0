"use client";

import {
  Fragment,
  forwardRef,
  useCallback,
  useImperativeHandle,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
  type MouseEvent,
  type ReactNode,
  type UIEvent,
} from "react";
import { getQuizAutocompleteSuggestions } from "../model/quiz-markdown";
import { EditorPaneHeader } from "./editor-pane-header";

const EDITOR_LINE_HEIGHT = 24;

export interface QuizMarkdownEditorFocusOptions {
  readonly suppressAutocomplete?: boolean;
}

export interface QuizMarkdownCodeEditorHandle {
  focusLocation(
    line: number,
    column: number,
    options?: QuizMarkdownEditorFocusOptions,
  ): void;
  focusOffset(offset: number, options?: QuizMarkdownEditorFocusOptions): void;
}

export interface QuizMarkdownCodeEditorProps {
  readonly disabled?: boolean;
  readonly onChange: (value: string) => void;
  readonly value: string;
}

function offsetForLocation(
  source: string,
  line: number,
  column: number,
): number {
  const targetLine = Math.max(1, line);
  const targetColumn = Math.max(1, column);
  let currentLine = 1;
  let offset = 0;

  while (currentLine < targetLine && offset < source.length) {
    if (source[offset] === "\r" && source[offset + 1] === "\n") {
      offset += 2;
      currentLine += 1;
    } else if (source[offset] === "\r" || source[offset] === "\n") {
      offset += 1;
      currentLine += 1;
    } else {
      offset += 1;
    }
  }
  return Math.min(source.length, offset + targetColumn - 1);
}

function lineForOffset(source: string, offset: number): number {
  return source.slice(0, Math.max(0, offset)).split(/\r\n|\r|\n/).length;
}

function normalizedTextareaValue(source: string): string {
  return source.replace(/\r\n|\r/g, "\n");
}

function sourceOffsetFromTextareaOffset(
  source: string,
  textareaOffset: number,
): number {
  const target = Math.max(0, textareaOffset);
  let normalizedOffset = 0;
  let sourceOffset = 0;
  while (sourceOffset < source.length && normalizedOffset < target) {
    if (source[sourceOffset] === "\r" && source[sourceOffset + 1] === "\n") {
      sourceOffset += 2;
    } else {
      sourceOffset += 1;
    }
    normalizedOffset += 1;
  }
  return sourceOffset;
}

function textareaOffsetFromSourceOffset(
  source: string,
  sourceOffset: number,
): number {
  return normalizedTextareaValue(
    source.slice(0, Math.max(0, Math.min(sourceOffset, source.length))),
  ).length;
}

function preferredLineEnding(source: string): "\r\n" | "\r" | "\n" {
  if (source.includes("\r\n")) {
    return "\r\n";
  }
  if (source.includes("\r")) {
    return "\r";
  }
  return "\n";
}

function preserveSourceLineEndings(
  source: string,
  nextTextareaValue: string,
): string {
  const previousTextareaValue = normalizedTextareaValue(source);
  let prefixLength = 0;
  while (
    prefixLength < previousTextareaValue.length &&
    prefixLength < nextTextareaValue.length &&
    previousTextareaValue[prefixLength] === nextTextareaValue[prefixLength]
  ) {
    prefixLength += 1;
  }

  let suffixLength = 0;
  while (
    suffixLength < previousTextareaValue.length - prefixLength &&
    suffixLength < nextTextareaValue.length - prefixLength &&
    previousTextareaValue[previousTextareaValue.length - 1 - suffixLength] ===
      nextTextareaValue[nextTextareaValue.length - 1 - suffixLength]
  ) {
    suffixLength += 1;
  }

  const sourceEditStart = sourceOffsetFromTextareaOffset(source, prefixLength);
  const sourceEditEnd = sourceOffsetFromTextareaOffset(
    source,
    previousTextareaValue.length - suffixLength,
  );
  const insertedTextareaText = nextTextareaValue.slice(
    prefixLength,
    nextTextareaValue.length - suffixLength,
  );
  const insertedSourceText = insertedTextareaText.replace(
    /\n/g,
    preferredLineEnding(source),
  );
  return (
    source.slice(0, sourceEditStart) +
    insertedSourceText +
    source.slice(sourceEditEnd)
  );
}

interface EditorSelection {
  readonly end: number;
  readonly start: number;
}

interface IndentationResult {
  readonly selection: EditorSelection;
  readonly value: string;
}

function lineStartForOffset(source: string, offset: number): number {
  const safeOffset = Math.max(0, Math.min(offset, source.length));
  return (
    Math.max(
      source.lastIndexOf("\n", safeOffset - 1),
      source.lastIndexOf("\r", safeOffset - 1),
    ) + 1
  );
}

function selectedLineStarts(
  source: string,
  selectionStart: number,
  selectionEnd: number,
): number[] {
  const first = lineStartForOffset(source, selectionStart);
  const effectiveEnd =
    selectionEnd > selectionStart &&
    lineStartForOffset(source, selectionEnd) === selectionEnd
      ? selectionEnd - 1
      : selectionEnd;
  const starts = [first];

  for (let offset = first; offset < effectiveEnd; offset += 1) {
    if (source[offset] === "\r" && source[offset + 1] === "\n") {
      starts.push(offset + 2);
      offset += 1;
    } else if (source[offset] === "\r" || source[offset] === "\n") {
      starts.push(offset + 1);
    }
  }
  return starts;
}

function editIndentation(
  source: string,
  selectionStart: number,
  selectionEnd: number,
  outdent: boolean,
): IndentationResult | null {
  if (selectionStart === selectionEnd && !outdent) {
    return {
      selection: {
        start: selectionStart + 1,
        end: selectionEnd + 1,
      },
      value:
        source.slice(0, selectionStart) + "\t" + source.slice(selectionEnd),
    };
  }

  const lineStarts = selectedLineStarts(source, selectionStart, selectionEnd);
  if (!outdent) {
    const value = [...lineStarts]
      .reverse()
      .reduce(
        (current, lineStart) =>
          current.slice(0, lineStart) + "\t" + current.slice(lineStart),
        source,
      );
    return {
      selection: {
        start: selectionStart + 1,
        end: selectionEnd + lineStarts.length,
      },
      value,
    };
  }

  const removals = lineStarts.filter((lineStart) => source[lineStart] === "\t");
  if (removals.length === 0) {
    return null;
  }
  const value = [...removals]
    .reverse()
    .reduce(
      (current, lineStart) =>
        current.slice(0, lineStart) + current.slice(lineStart + 1),
      source,
    );
  return {
    selection: {
      start:
        selectionStart -
        removals.filter((lineStart) => lineStart < selectionStart).length,
      end:
        selectionEnd -
        removals.filter((lineStart) => lineStart < selectionEnd).length,
    },
    value,
  };
}

function fenceLength(line: string): number | null {
  const match = /^(`{3,})([^`]*)$/.exec(line);
  return match ? match[1].length : null;
}

function highlightedLine(line: string, inFence: boolean): ReactNode {
  if (fenceLength(line) !== null) {
    return <span className="font-semibold text-warning">{line}</span>;
  }
  if (inFence) {
    return <span className="text-foreground-secondary">{line}</span>;
  }

  const header =
    /^(Câu )([0-9]+)( \[)(SINGLE_CHOICE|MULTIPLE_CHOICE|TRUE_FALSE_MATRIX|NUMERIC_FILL)(\]:)(.*)$/.exec(
      line,
    );
  if (header) {
    return (
      <>
        <span className="font-semibold text-primary">{header[1]}</span>
        <span className="font-semibold text-secondary">{header[2]}</span>
        <span className="font-semibold text-primary">{header[3]}</span>
        <span className="font-semibold text-secondary">{header[4]}</span>
        <span className="font-semibold text-primary">{header[5]}</span>
        <span className="text-foreground">{header[6]}</span>
      </>
    );
  }

  const option = /^(\*)?([A-D])(\.)(.*)$/.exec(line);
  if (option) {
    return (
      <>
        {option[1] ? <span className="font-bold text-secondary">*</span> : null}
        <span className="font-semibold text-primary">{option[2]}</span>
        <span className="font-semibold text-primary">{option[3]}</span>
        <span className="text-foreground">{option[4]}</span>
      </>
    );
  }

  const answer = /^(Đáp án:)(.*)$/.exec(line);
  if (answer) {
    return (
      <>
        <span className="font-semibold text-secondary">{answer[1]}</span>
        <span className="text-foreground">{answer[2]}</span>
      </>
    );
  }

  const explanation = /^(Lời giải:)(.*)$/.exec(line);
  if (explanation) {
    return (
      <>
        <span className="font-semibold text-primary">{explanation[1]}</span>
        <span className="text-foreground">{explanation[2]}</span>
      </>
    );
  }

  return <span className="text-foreground">{line}</span>;
}

function HighlightedSource({ value }: { readonly value: string }) {
  const lines = value.split(/\r\n|\r|\n/);
  let openFence: number | null = null;

  return lines.map((line, index) => {
    const wasInFence = openFence !== null;
    const currentFence = fenceLength(line);
    const content = highlightedLine(line, wasInFence);

    if (currentFence !== null) {
      if (openFence === null) {
        openFence = currentFence;
      } else if (
        currentFence >= openFence &&
        line.slice(currentFence).trim() === ""
      ) {
        openFence = null;
      }
    }

    return (
      <Fragment key={index}>
        {content}
        {index < lines.length - 1 ? "\n" : null}
      </Fragment>
    );
  });
}

export const QuizMarkdownCodeEditor = forwardRef<
  QuizMarkdownCodeEditorHandle,
  QuizMarkdownCodeEditorProps
>(function QuizMarkdownCodeEditor({ disabled = false, onChange, value }, ref) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const [caret, setCaret] = useState(0);
  const [activeIndex, setActiveIndex] = useState(0);
  const [dismissedSignature, setDismissedSignature] = useState<string | null>(
    null,
  );
  const pendingSelectionRef = useRef<EditorSelection | null>(null);
  const [scroll, setScroll] = useState({ left: 0, top: 0 });
  const rawSuggestions = useMemo(
    () => getQuizAutocompleteSuggestions(value, caret),
    [caret, value],
  );
  const signature = `${value}\u0000${caret}`;
  const suggestions = dismissedSignature === signature ? [] : rawSuggestions;
  const lines = value.split(/\r\n|\r|\n/);
  const activeLine = lineForOffset(value, caret);

  const positionEditorSelection = useCallback(
    (selection: EditorSelection) => {
      const safeStart = Math.max(0, Math.min(selection.start, value.length));
      const safeEnd = Math.max(
        safeStart,
        Math.min(selection.end, value.length),
      );
      const textarea = textareaRef.current;
      if (textarea === null) {
        return;
      }
      textarea.focus();
      textarea.setSelectionRange(
        textareaOffsetFromSourceOffset(value, safeStart),
        textareaOffsetFromSourceOffset(value, safeEnd),
      );
      const line = lineForOffset(value, safeStart);
      const targetTop = Math.max(
        0,
        (line - 1) * EDITOR_LINE_HEIGHT - textarea.clientHeight / 2,
      );
      textarea.scrollTop = targetTop;
      setScroll({ left: textarea.scrollLeft, top: targetTop });
      setCaret(safeStart);
    },
    [value],
  );

  const focusEditorOffset = useCallback(
    (offset: number, options: QuizMarkdownEditorFocusOptions = {}) => {
      const safeOffset = Math.max(0, Math.min(offset, value.length));
      setDismissedSignature(
        options.suppressAutocomplete ? `${value}\u0000${safeOffset}` : null,
      );
      positionEditorSelection({ start: safeOffset, end: safeOffset });
    },
    [positionEditorSelection, value],
  );

  useLayoutEffect(() => {
    const pendingSelection = pendingSelectionRef.current;
    if (pendingSelection === null) {
      return;
    }
    pendingSelectionRef.current = null;
    positionEditorSelection(pendingSelection);
  }, [positionEditorSelection, value]);

  useImperativeHandle(
    ref,
    () => ({
      focusLocation(line, column, options) {
        focusEditorOffset(offsetForLocation(value, line, column), options);
      },
      focusOffset(offset, options) {
        focusEditorOffset(offset, options);
      },
    }),
    [focusEditorOffset, value],
  );

  function syncCaret(target: HTMLTextAreaElement) {
    setCaret(sourceOffsetFromTextareaOffset(value, target.selectionStart ?? 0));
    setDismissedSignature(null);
  }

  function resetCaret(target: HTMLTextAreaElement) {
    syncCaret(target);
    setActiveIndex(0);
  }

  function handleChange(event: ChangeEvent<HTMLTextAreaElement>) {
    const nextValue = preserveSourceLineEndings(value, event.target.value);
    onChange(nextValue);
    setCaret(
      sourceOffsetFromTextareaOffset(
        nextValue,
        event.target.selectionStart ?? event.target.value.length,
      ),
    );
    setActiveIndex(0);
    setDismissedSignature(null);
  }

  function handleScroll(event: UIEvent<HTMLTextAreaElement>) {
    setScroll({
      left: event.currentTarget.scrollLeft,
      top: event.currentTarget.scrollTop,
    });
  }

  function acceptSuggestion(index: number) {
    const suggestion = suggestions[index];
    if (suggestion === undefined) {
      return;
    }
    const nextValue =
      value.slice(0, suggestion.replaceStart) +
      suggestion.insertText +
      value.slice(suggestion.replaceEnd);
    const nextCaret = suggestion.replaceStart + suggestion.insertText.length;
    onChange(nextValue);
    setDismissedSignature(`${nextValue}\u0000${nextCaret}`);
    pendingSelectionRef.current = { start: nextCaret, end: nextCaret };
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.nativeEvent.isComposing) {
      return;
    }

    if (suggestions.length > 0 && event.key === "ArrowDown") {
      event.preventDefault();
      setActiveIndex((current) => (current + 1) % suggestions.length);
      return;
    }
    if (suggestions.length > 0 && event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex(
        (current) => (current - 1 + suggestions.length) % suggestions.length,
      );
      return;
    }
    if (
      suggestions.length > 0 &&
      ((event.key === "Tab" && !event.shiftKey) || event.key === "Enter")
    ) {
      event.preventDefault();
      acceptSuggestion(activeIndex);
      return;
    }
    if (suggestions.length > 0 && event.key === "Escape") {
      event.preventDefault();
      setDismissedSignature(signature);
      return;
    }
    if (event.key === "Tab") {
      event.preventDefault();
      const target = event.currentTarget;
      const edit = editIndentation(
        value,
        sourceOffsetFromTextareaOffset(value, target.selectionStart ?? 0),
        sourceOffsetFromTextareaOffset(
          value,
          target.selectionEnd ?? target.selectionStart ?? 0,
        ),
        event.shiftKey,
      );
      if (edit === null) {
        return;
      }
      onChange(edit.value);
      setCaret(edit.selection.start);
      setActiveIndex(0);
      setDismissedSignature(`${edit.value}\u0000${edit.selection.start}`);
      pendingSelectionRef.current = edit.selection;
    }
  }

  function handleSuggestionMouseDown(event: MouseEvent<HTMLLIElement>) {
    event.preventDefault();
  }

  const activeSuggestionId =
    suggestions.length > 0
      ? `quiz-markdown-suggestion-${suggestions[activeIndex]?.id}`
      : undefined;

  return (
    <div className="relative flex h-full min-h-0 flex-col">
      <EditorPaneHeader
        className="mb-3"
        description="Column-1 completion stays canonical; publish validation remains authoritative."
        descriptionId="quiz-editor-help"
        htmlFor="quiz-markdown-source"
        title="Quiz Markdown source"
        titleId="quiz-editor-title"
      />

      <div className="relative min-h-[20rem] flex-1 overflow-hidden rounded-lg border border-border-strong bg-surface font-mono text-sm leading-6 shadow-inner focus-within:border-primary focus-within:ring-2 focus-within:ring-focus/30">
        <div
          aria-hidden="true"
          className="absolute inset-y-0 left-0 w-12 overflow-hidden border-r border-border bg-surface-muted text-right text-foreground-muted"
          data-testid="quiz-markdown-line-numbers"
        >
          <div
            className="px-2 py-3"
            style={{ transform: `translateY(-${scroll.top}px)` }}
          >
            {lines.map((_, index) => (
              <div
                className={
                  index + 1 === activeLine
                    ? "font-semibold text-primary"
                    : undefined
                }
                key={index}
                style={{ height: EDITOR_LINE_HEIGHT }}
              >
                {index + 1}
              </div>
            ))}
          </div>
        </div>

        <div className="absolute inset-y-0 left-12 right-0 overflow-hidden">
          <div
            aria-hidden="true"
            className="pointer-events-none absolute left-0 right-0 bg-primary/8"
            data-testid="quiz-markdown-active-line"
            style={{
              height: EDITOR_LINE_HEIGHT,
              top: 12 + (activeLine - 1) * EDITOR_LINE_HEIGHT - scroll.top,
            }}
          />
          <pre
            aria-hidden="true"
            className="pointer-events-none absolute left-0 top-0 min-h-full min-w-full whitespace-pre px-4 py-3"
            data-testid="quiz-markdown-highlight-layer"
            style={{
              transform: `translate(${-scroll.left}px, ${-scroll.top}px)`,
            }}
          >
            <HighlightedSource value={value} />
          </pre>
          <textarea
            aria-activedescendant={activeSuggestionId}
            aria-autocomplete="list"
            aria-controls="quiz-markdown-suggestions"
            aria-describedby="quiz-editor-help"
            autoCapitalize="off"
            autoCorrect="off"
            className="absolute inset-0 h-full w-full resize-none overflow-auto whitespace-pre bg-transparent px-4 py-3 text-transparent outline-none caret-foreground selection:bg-primary/20 disabled:cursor-not-allowed"
            disabled={disabled}
            id="quiz-markdown-source"
            onChange={handleChange}
            onClick={(event) => resetCaret(event.currentTarget)}
            onKeyDown={handleKeyDown}
            onKeyUp={(event) => {
              if (
                event.key === "Tab" ||
                (suggestions.length > 0 &&
                  ["ArrowDown", "ArrowUp", "Enter", "Escape"].includes(
                    event.key,
                  ))
              ) {
                return;
              }
              syncCaret(event.currentTarget);
              setActiveIndex(0);
            }}
            onScroll={handleScroll}
            onSelect={(event) =>
              setCaret(
                sourceOffsetFromTextareaOffset(
                  value,
                  event.currentTarget.selectionStart ?? 0,
                ),
              )
            }
            ref={textareaRef}
            spellCheck={false}
            style={{ caretColor: "var(--color-foreground)" }}
            value={value}
            wrap="off"
          />
        </div>
      </div>

      {suggestions.length > 0 ? (
        <ul
          aria-label="Quiz Markdown suggestions"
          className="absolute left-14 right-3 top-20 z-20 max-h-64 overflow-auto rounded-lg border border-border-strong bg-surface p-1 shadow-card"
          id="quiz-markdown-suggestions"
          role="listbox"
        >
          {suggestions.map((suggestion, index) => (
            <li
              aria-selected={index === activeIndex}
              className={`cursor-pointer rounded-md px-3 py-2 font-mono text-sm ${
                index === activeIndex
                  ? "bg-primary text-foreground-inverse"
                  : "text-foreground hover:bg-surface-muted"
              }`}
              id={`quiz-markdown-suggestion-${suggestion.id}`}
              key={suggestion.id}
              onClick={() => acceptSuggestion(index)}
              onMouseDown={handleSuggestionMouseDown}
              role="option"
            >
              {suggestion.label}
            </li>
          ))}
        </ul>
      ) : null}

      <p aria-live="polite" className="sr-only">
        {suggestions.length > 0
          ? `${suggestions.length} Quiz Markdown suggestions available.`
          : ""}
      </p>
    </div>
  );
});

QuizMarkdownCodeEditor.displayName = "QuizMarkdownCodeEditor";
