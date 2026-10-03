"use client";

import {
  Fragment,
  forwardRef,
  useCallback,
  useEffect,
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
import {
  QUIZ_MARKDOWN_TAB_SIZE,
  preserveQuizMarkdownTextareaEdit,
  quizMarkdownPopupGeometry,
  sourceLineBoundsAtOffset,
  sourceLocationToOffset,
  sourceOffsetFromTextareaOffset,
  sourceOffsetToLocation,
  sourceOffsetToVisualLocation,
  textareaOffsetFromSourceOffset,
} from "../model/quiz-markdown-coordinates";
import { EditorPaneHeader } from "./editor-pane-header";

const EDITOR_LINE_HEIGHT = 24;
const EDITOR_GUTTER_WIDTH = 48;
const EDITOR_PADDING_LEFT = 16;
const EDITOR_PADDING_TOP = 12;

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

interface EditorSelection {
  readonly end: number;
  readonly start: number;
}

interface IndentationResult {
  readonly selection: EditorSelection;
  readonly value: string;
}

function measuredCharacterWidth(
  textarea: HTMLTextAreaElement,
  container: HTMLElement,
): number {
  const computed = window.getComputedStyle(textarea);
  const probe = document.createElement("span");
  probe.setAttribute("aria-hidden", "true");
  probe.textContent = "0000000000";
  probe.style.font = computed.font;
  probe.style.letterSpacing = computed.letterSpacing;
  probe.style.position = "absolute";
  probe.style.visibility = "hidden";
  probe.style.whiteSpace = "pre";
  container.append(probe);
  const measured = probe.getBoundingClientRect().width / 10;
  probe.remove();
  if (measured > 0) {
    return measured;
  }
  const fontSize = Number.parseFloat(computed.fontSize);
  return Number.isFinite(fontSize) ? fontSize * 0.6 : 8.4;
}

function lineStartForOffset(source: string, offset: number): number {
  return sourceLineBoundsAtOffset(source, offset).start;
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
    return <span className="text-warning">{line}</span>;
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
        <span className="text-primary">{header[1]}</span>
        <span className="text-secondary">{header[2]}</span>
        <span className="text-primary">{header[3]}</span>
        <span className="text-secondary">{header[4]}</span>
        <span className="text-primary">{header[5]}</span>
        <span className="text-foreground">{header[6]}</span>
      </>
    );
  }

  const option = /^(\*)?([A-D])(\.)(.*)$/.exec(line);
  if (option) {
    return (
      <>
        {option[1] ? <span className="text-secondary">*</span> : null}
        <span className="text-primary">{option[2]}</span>
        <span className="text-primary">{option[3]}</span>
        <span className="text-foreground">{option[4]}</span>
      </>
    );
  }

  const answer = /^(Đáp án:)(.*)$/.exec(line);
  if (answer) {
    return (
      <>
        <span className="text-secondary">{answer[1]}</span>
        <span className="text-foreground">{answer[2]}</span>
      </>
    );
  }

  const explanation = /^(Lời giải:)(.*)$/.exec(line);
  if (explanation) {
    return (
      <>
        <span className="text-primary">{explanation[1]}</span>
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
  const editorFrameRef = useRef<HTMLDivElement>(null);
  const popupRef = useRef<HTMLUListElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const [caret, setCaret] = useState(0);
  const [hasTextSelection, setHasTextSelection] = useState(false);
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
  const suggestions =
    hasTextSelection || dismissedSignature === signature ? [] : rawSuggestions;
  const lines = value.split(/\r\n|\r|\n/);
  const activeLine = sourceOffsetToLocation(value, caret).line;
  const popupSignature = `${signature}\u0000${scroll.left}\u0000${scroll.top}\u0000${suggestions
    .map((suggestion) => suggestion.id)
    .join("|")}`;

  useEffect(() => {
    const textarea = textareaRef.current;
    if (textarea === null) {
      return;
    }
    const selectionTarget = textarea;

    function handleSelectionChange() {
      if (document.activeElement !== selectionTarget) {
        return;
      }
      const selectionStart = selectionTarget.selectionStart ?? 0;
      const selectionEnd = selectionTarget.selectionEnd ?? selectionStart;
      setCaret(sourceOffsetFromTextareaOffset(value, selectionStart));
      setHasTextSelection(selectionStart !== selectionEnd);
    }

    document.addEventListener("selectionchange", handleSelectionChange);
    return () =>
      document.removeEventListener("selectionchange", handleSelectionChange);
  }, [value]);

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
      const line = sourceOffsetToLocation(value, safeStart).line;
      const targetTop = Math.max(
        0,
        (line - 1) * EDITOR_LINE_HEIGHT - textarea.clientHeight / 2,
      );
      textarea.scrollTop = targetTop;
      setScroll({ left: textarea.scrollLeft, top: textarea.scrollTop });
      setCaret(safeStart);
      setHasTextSelection(safeStart !== safeEnd);
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

  useLayoutEffect(() => {
    if (suggestions.length === 0) {
      return;
    }
    const frame = editorFrameRef.current;
    const popup = popupRef.current;
    const textarea = textareaRef.current;
    if (frame === null || popup === null || textarea === null) {
      return;
    }

    const frameRect = frame.getBoundingClientRect();
    const popupRect = popup.getBoundingClientRect();
    const viewportWidth = frame.clientWidth || frameRect.width;
    const viewportHeight = frame.clientHeight || frameRect.height;
    const popupWidth =
      popupRect.width || Math.min(448, Math.max(200, viewportWidth - 56));
    const popupHeight =
      popupRect.height || Math.min(256, suggestions.length * 40 + 8);
    const visual = sourceOffsetToVisualLocation(value, caret);
    const geometry = quizMarkdownPopupGeometry({
      caretLine: visual.line,
      caretVisualColumn: visual.visualColumn,
      characterWidth: measuredCharacterWidth(textarea, frame),
      gutterWidth: EDITOR_GUTTER_WIDTH,
      lineHeight: EDITOR_LINE_HEIGHT,
      paddingLeft: EDITOR_PADDING_LEFT,
      paddingTop: EDITOR_PADDING_TOP,
      popupHeight,
      popupWidth,
      scrollLeft: scroll.left,
      scrollTop: scroll.top,
      viewportHeight,
      viewportWidth,
    });
    popup.style.left = `${geometry.left}px`;
    popup.style.top = `${geometry.top}px`;
    popup.style.visibility = "visible";
    popup.dataset.placement = geometry.placement;
  }, [
    caret,
    popupSignature,
    scroll.left,
    scroll.top,
    suggestions.length,
    value,
  ]);

  useImperativeHandle(
    ref,
    () => ({
      focusLocation(line, column, options) {
        focusEditorOffset(sourceLocationToOffset(value, line, column), options);
      },
      focusOffset(offset, options) {
        focusEditorOffset(offset, options);
      },
    }),
    [focusEditorOffset, value],
  );

  function syncCaret(target: HTMLTextAreaElement) {
    setCaret(sourceOffsetFromTextareaOffset(value, target.selectionStart ?? 0));
    setHasTextSelection(
      (target.selectionStart ?? 0) !== (target.selectionEnd ?? 0),
    );
    setDismissedSignature(null);
  }

  function resetCaret(target: HTMLTextAreaElement) {
    syncCaret(target);
    setActiveIndex(0);
  }

  function handleChange(event: ChangeEvent<HTMLTextAreaElement>) {
    const nextValue = preserveQuizMarkdownTextareaEdit(
      value,
      event.target.value,
    );
    onChange(nextValue);
    setCaret(
      sourceOffsetFromTextareaOffset(
        nextValue,
        event.target.selectionStart ?? event.target.value.length,
      ),
    );
    setHasTextSelection(
      (event.target.selectionStart ?? 0) !== (event.target.selectionEnd ?? 0),
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
    setHasTextSelection(false);
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
      setHasTextSelection(edit.selection.start !== edit.selection.end);
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

      <div
        className="relative min-h-[20rem] flex-1 overflow-hidden rounded-lg border border-border-strong bg-surface font-mono text-sm leading-6 shadow-inner focus-within:border-primary focus-within:ring-2 focus-within:ring-focus/30"
        data-testid="quiz-markdown-editor-frame"
        ref={editorFrameRef}
      >
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
            className="pointer-events-none absolute left-0 top-0 min-h-full min-w-full whitespace-pre px-4 py-3 font-mono font-normal"
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
            className="absolute inset-0 h-full w-full resize-none overflow-auto whitespace-pre bg-transparent px-4 py-3 font-mono font-normal text-transparent outline-none caret-foreground selection:bg-primary/20 disabled:cursor-not-allowed"
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
            onMouseUp={(event) => resetCaret(event.currentTarget)}
            onScroll={handleScroll}
            onSelect={(event) => {
              setCaret(
                sourceOffsetFromTextareaOffset(
                  value,
                  event.currentTarget.selectionStart ?? 0,
                ),
              );
              setHasTextSelection(
                (event.currentTarget.selectionStart ?? 0) !==
                  (event.currentTarget.selectionEnd ?? 0),
              );
            }}
            ref={textareaRef}
            spellCheck={false}
            style={{
              caretColor: "var(--color-foreground)",
              tabSize: QUIZ_MARKDOWN_TAB_SIZE,
            }}
            value={value}
            wrap="off"
          />
        </div>
        {suggestions.length > 0 ? (
          <ul
            aria-label="Quiz Markdown suggestions"
            className="absolute z-20 max-h-64 overflow-auto rounded-lg border border-border-strong bg-surface p-1 shadow-card"
            id="quiz-markdown-suggestions"
            ref={popupRef}
            role="listbox"
            style={{
              left: 0,
              top: 0,
              visibility: "hidden",
              width: "min(28rem, calc(100% - 3.5rem))",
            }}
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
      </div>

      <p aria-live="polite" className="sr-only">
        {suggestions.length > 0
          ? `${suggestions.length} Quiz Markdown suggestions available.`
          : ""}
      </p>
    </div>
  );
});

QuizMarkdownCodeEditor.displayName = "QuizMarkdownCodeEditor";
