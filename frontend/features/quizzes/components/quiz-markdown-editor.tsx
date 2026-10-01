"use client";

import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
  type MouseEvent,
} from "react";
import { getQuizAutocompleteSuggestions } from "../model/quiz-markdown";

export interface QuizMarkdownEditorHandle {
  focusLocation(line: number, column: number): void;
}

export interface QuizMarkdownEditorProps {
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

export const QuizMarkdownEditor = forwardRef<
  QuizMarkdownEditorHandle,
  QuizMarkdownEditorProps
>(function QuizMarkdownEditor({ disabled = false, onChange, value }, ref) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const [caret, setCaret] = useState(0);
  const [activeIndex, setActiveIndex] = useState(0);
  const [dismissedSignature, setDismissedSignature] = useState<string | null>(
    null,
  );
  const [pendingSelection, setPendingSelection] = useState<number | null>(null);
  const rawSuggestions = useMemo(
    () => getQuizAutocompleteSuggestions(value, caret),
    [caret, value],
  );
  const signature = `${value}\u0000${caret}`;
  const suggestions = dismissedSignature === signature ? [] : rawSuggestions;
  const suggestionKey = suggestions
    .map((suggestion) => suggestion.id)
    .join("|");

  useEffect(() => {
    setActiveIndex(0);
  }, [suggestionKey]);

  useLayoutEffect(() => {
    if (pendingSelection === null) {
      return;
    }
    const textarea = textareaRef.current;
    if (textarea !== null) {
      textarea.focus();
      textarea.setSelectionRange(pendingSelection, pendingSelection);
      setCaret(pendingSelection);
    }
    setPendingSelection(null);
  }, [pendingSelection, value]);

  useImperativeHandle(
    ref,
    () => ({
      focusLocation(line, column) {
        const offset = offsetForLocation(value, line, column);
        const textarea = textareaRef.current;
        if (textarea !== null) {
          textarea.focus();
          textarea.setSelectionRange(offset, offset);
          setCaret(offset);
        }
      },
    }),
    [value],
  );

  function syncCaret(target: HTMLTextAreaElement) {
    setCaret(target.selectionStart ?? 0);
    setDismissedSignature(null);
  }

  function handleChange(event: ChangeEvent<HTMLTextAreaElement>) {
    onChange(event.target.value);
    setCaret(event.target.selectionStart ?? event.target.value.length);
    setDismissedSignature(null);
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
    setPendingSelection(nextCaret);
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
    <div className="relative">
      <label
        className="mb-2 block text-sm font-semibold text-foreground-secondary"
        htmlFor="quiz-markdown-source"
      >
        Quiz Markdown source
      </label>
      <p
        className="mb-3 text-sm leading-6 text-foreground-muted"
        id="quiz-editor-help"
      >
        Structural completion works at column 1 outside fenced code blocks. The
        backend remains authoritative when publishing.
      </p>
      <textarea
        aria-activedescendant={activeSuggestionId}
        aria-autocomplete="list"
        aria-controls="quiz-markdown-suggestions"
        aria-describedby="quiz-editor-help"
        autoCapitalize="off"
        autoCorrect="off"
        className="min-h-[30rem] w-full resize-y rounded-lg border border-border-strong bg-surface px-4 py-3 font-mono text-sm leading-6 text-foreground shadow-inner outline-none transition-colors duration-200 placeholder:text-foreground-muted focus:border-primary focus:ring-2 focus:ring-focus/30 disabled:cursor-not-allowed disabled:bg-surface-muted motion-reduce:transition-none"
        disabled={disabled}
        id="quiz-markdown-source"
        onChange={handleChange}
        onClick={(event) => syncCaret(event.currentTarget)}
        onKeyDown={handleKeyDown}
        onKeyUp={(event) => syncCaret(event.currentTarget)}
        onSelect={(event) => syncCaret(event.currentTarget)}
        ref={textareaRef}
        spellCheck={false}
        value={value}
      />

      {suggestions.length > 0 ? (
        <ul
          aria-label="Quiz Markdown suggestions"
          className="absolute left-3 right-3 top-[8.3rem] z-20 max-h-64 overflow-auto rounded-lg border border-border-strong bg-surface p-1 shadow-card"
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

QuizMarkdownEditor.displayName = "QuizMarkdownEditor";
