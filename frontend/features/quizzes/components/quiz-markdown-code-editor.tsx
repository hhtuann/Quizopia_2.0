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

const EDITOR_LINE_HEIGHT = 24;

export interface QuizMarkdownCodeEditorHandle {
  focusLocation(line: number, column: number): void;
  focusOffset(offset: number): void;
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
  const pendingSelectionRef = useRef<number | null>(null);
  const [scroll, setScroll] = useState({ left: 0, top: 0 });
  const rawSuggestions = useMemo(
    () => getQuizAutocompleteSuggestions(value, caret),
    [caret, value],
  );
  const signature = `${value}\u0000${caret}`;
  const suggestions = dismissedSignature === signature ? [] : rawSuggestions;
  const lines = value.split(/\r\n|\r|\n/);
  const activeLine = lineForOffset(value, caret);

  const focusEditorOffset = useCallback(
    (offset: number) => {
      const safeOffset = Math.max(0, Math.min(offset, value.length));
      const textarea = textareaRef.current;
      if (textarea === null) {
        return;
      }
      textarea.focus();
      textarea.setSelectionRange(safeOffset, safeOffset);
      const line = lineForOffset(value, safeOffset);
      const targetTop = Math.max(
        0,
        (line - 1) * EDITOR_LINE_HEIGHT - textarea.clientHeight / 2,
      );
      textarea.scrollTop = targetTop;
      setScroll({ left: textarea.scrollLeft, top: targetTop });
      setCaret(safeOffset);
    },
    [value],
  );

  useLayoutEffect(() => {
    const pendingSelection = pendingSelectionRef.current;
    if (pendingSelection === null) {
      return;
    }
    pendingSelectionRef.current = null;
    focusEditorOffset(pendingSelection);
  }, [focusEditorOffset, value]);

  useImperativeHandle(
    ref,
    () => ({
      focusLocation(line, column) {
        focusEditorOffset(offsetForLocation(value, line, column));
      },
      focusOffset(offset) {
        focusEditorOffset(offset);
      },
    }),
    [focusEditorOffset, value],
  );

  function syncCaret(target: HTMLTextAreaElement) {
    setCaret(target.selectionStart ?? 0);
    setDismissedSignature(null);
  }

  function resetCaret(target: HTMLTextAreaElement) {
    syncCaret(target);
    setActiveIndex(0);
  }

  function handleChange(event: ChangeEvent<HTMLTextAreaElement>) {
    onChange(event.target.value);
    setCaret(event.target.selectionStart ?? event.target.value.length);
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
    pendingSelectionRef.current = nextCaret;
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.nativeEvent.isComposing || suggestions.length === 0) {
      return;
    }

    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveIndex((current) => (current + 1) % suggestions.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex(
        (current) => (current - 1 + suggestions.length) % suggestions.length,
      );
    } else if (event.key === "Tab" || event.key === "Enter") {
      event.preventDefault();
      acceptSuggestion(activeIndex);
    } else if (event.key === "Escape") {
      event.preventDefault();
      setDismissedSignature(signature);
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
      <div className="mb-3 shrink-0">
        <label
          className="block text-sm font-semibold text-foreground-secondary"
          htmlFor="quiz-markdown-source"
        >
          Quiz Markdown source
        </label>
        <p
          className="mt-1 text-xs leading-5 text-foreground-muted"
          id="quiz-editor-help"
        >
          Column-1 completion stays canonical; publish validation remains
          authoritative.
        </p>
      </div>

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
              syncCaret(event.currentTarget);
              if (
                event.key !== "ArrowDown" &&
                event.key !== "ArrowUp" &&
                event.key !== "Enter" &&
                event.key !== "Tab" &&
                event.key !== "Escape"
              ) {
                setActiveIndex(0);
              }
            }}
            onScroll={handleScroll}
            onSelect={(event) => syncCaret(event.currentTarget)}
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
