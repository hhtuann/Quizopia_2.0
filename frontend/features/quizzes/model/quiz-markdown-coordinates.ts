export const QUIZ_MARKDOWN_TAB_SIZE = 4;

export interface QuizMarkdownSourceLocation {
  readonly column: number;
  readonly line: number;
}

export interface QuizMarkdownVisualLocation extends QuizMarkdownSourceLocation {
  readonly visualColumn: number;
}

export interface QuizMarkdownLineBounds {
  readonly contentEnd: number;
  readonly end: number;
  readonly start: number;
}

export function clampSourceOffset(source: string, offset: number): number {
  return Math.max(0, Math.min(offset, source.length));
}

export function normalizeQuizMarkdownForTextarea(source: string): string {
  return source.replace(/\r\n|\r/g, "\n");
}

export function sourceOffsetFromTextareaOffset(
  source: string,
  textareaOffset: number,
): number {
  const target = Math.max(0, textareaOffset);
  let normalizedOffset = 0;
  let sourceOffset = 0;

  while (sourceOffset < source.length && normalizedOffset < target) {
    sourceOffset +=
      source[sourceOffset] === "\r" && source[sourceOffset + 1] === "\n"
        ? 2
        : 1;
    normalizedOffset += 1;
  }
  return sourceOffset;
}

export function textareaOffsetFromSourceOffset(
  source: string,
  sourceOffset: number,
): number {
  return normalizeQuizMarkdownForTextarea(
    source.slice(0, clampSourceOffset(source, sourceOffset)),
  ).length;
}

export function quizMarkdownLineStarts(source: string): readonly number[] {
  const starts = [0];
  for (let offset = 0; offset < source.length; offset += 1) {
    if (source[offset] === "\r" && source[offset + 1] === "\n") {
      starts.push(offset + 2);
      offset += 1;
    } else if (source[offset] === "\r" || source[offset] === "\n") {
      starts.push(offset + 1);
    }
  }
  return starts;
}

export function sourceLineBoundsAtOffset(
  source: string,
  sourceOffset: number,
): QuizMarkdownLineBounds {
  const safeOffset = clampSourceOffset(source, sourceOffset);
  let start = 0;
  for (let offset = safeOffset - 1; offset >= 0; offset -= 1) {
    if (source[offset] === "\n" || source[offset] === "\r") {
      start = offset + 1;
      break;
    }
  }

  let contentEnd = source.length;
  let end = source.length;
  for (let offset = safeOffset; offset < source.length; offset += 1) {
    if (source[offset] === "\r") {
      contentEnd = offset;
      end = source[offset + 1] === "\n" ? offset + 2 : offset + 1;
      break;
    }
    if (source[offset] === "\n") {
      contentEnd = offset;
      end = offset + 1;
      break;
    }
  }
  return { contentEnd, end, start };
}

export function sourceOffsetToLocation(
  source: string,
  sourceOffset: number,
): QuizMarkdownSourceLocation {
  const textareaOffset = textareaOffsetFromSourceOffset(source, sourceOffset);
  const normalized = normalizeQuizMarkdownForTextarea(source).slice(
    0,
    textareaOffset,
  );
  const lineStart = normalized.lastIndexOf("\n") + 1;
  return {
    column: normalized.length - lineStart + 1,
    line: normalized.split("\n").length,
  };
}

export function sourceLocationToOffset(
  source: string,
  line: number,
  column: number,
): number {
  const starts = quizMarkdownLineStarts(source);
  const lineIndex = Math.max(0, Math.min(line - 1, starts.length - 1));
  const start = starts[lineIndex] ?? 0;
  const bounds = sourceLineBoundsAtOffset(source, start);
  return Math.min(bounds.contentEnd, start + Math.max(0, column - 1));
}

export function sourceOffsetToVisualLocation(
  source: string,
  sourceOffset: number,
  tabSize = QUIZ_MARKDOWN_TAB_SIZE,
): QuizMarkdownVisualLocation {
  const safeOffset = clampSourceOffset(source, sourceOffset);
  const location = sourceOffsetToLocation(source, safeOffset);
  const bounds = sourceLineBoundsAtOffset(source, safeOffset);
  const linePrefix = source.slice(bounds.start, safeOffset);
  let visualColumn = 0;

  for (const character of linePrefix) {
    visualColumn +=
      character === "\t"
        ? tabSize - (visualColumn % tabSize)
        : character === "\r" || character === "\n"
          ? 0
          : 1;
  }
  return { ...location, visualColumn };
}

export function preferredQuizMarkdownLineEnding(
  source: string,
): "\r\n" | "\r" | "\n" {
  if (source.includes("\r\n")) {
    return "\r\n";
  }
  if (source.includes("\r")) {
    return "\r";
  }
  return "\n";
}

export function preserveQuizMarkdownTextareaEdit(
  source: string,
  nextTextareaValue: string,
): string {
  const previousTextareaValue = normalizeQuizMarkdownForTextarea(source);
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
  const insertedText = nextTextareaValue
    .slice(prefixLength, nextTextareaValue.length - suffixLength)
    .replace(/\n/g, preferredQuizMarkdownLineEnding(source));
  return (
    source.slice(0, sourceEditStart) +
    insertedText +
    source.slice(sourceEditEnd)
  );
}

export interface QuizMarkdownPopupGeometryInput {
  readonly caretLine: number;
  readonly caretVisualColumn: number;
  readonly characterWidth: number;
  readonly gutterWidth: number;
  readonly lineHeight: number;
  readonly paddingLeft: number;
  readonly paddingTop: number;
  readonly popupHeight: number;
  readonly popupWidth: number;
  readonly scrollLeft: number;
  readonly scrollTop: number;
  readonly viewportHeight: number;
  readonly viewportWidth: number;
}

export interface QuizMarkdownPopupGeometry {
  readonly left: number;
  readonly placement: "above" | "below";
  readonly top: number;
}

export function quizMarkdownPopupGeometry({
  caretLine,
  caretVisualColumn,
  characterWidth,
  gutterWidth,
  lineHeight,
  paddingLeft,
  paddingTop,
  popupHeight,
  popupWidth,
  scrollLeft,
  scrollTop,
  viewportHeight,
  viewportWidth,
}: QuizMarkdownPopupGeometryInput): QuizMarkdownPopupGeometry {
  const edge = 4;
  const gap = 4;
  const caretTop = paddingTop + (caretLine - 1) * lineHeight - scrollTop;
  const desiredLeft =
    gutterWidth + paddingLeft + caretVisualColumn * characterWidth - scrollLeft;
  const minimumLeft = gutterWidth + edge;
  const maximumLeft = Math.max(minimumLeft, viewportWidth - popupWidth - edge);
  const below = caretTop + lineHeight + gap;
  const above = caretTop - popupHeight - gap;
  const fitsBelow = below + popupHeight <= viewportHeight - edge;
  const placement = fitsBelow ? "below" : "above";
  const desiredTop = fitsBelow ? below : above;

  return {
    left: Math.max(minimumLeft, Math.min(desiredLeft, maximumLeft)),
    placement,
    top: Math.max(
      edge,
      Math.min(desiredTop, Math.max(edge, viewportHeight - popupHeight - edge)),
    ),
  };
}
