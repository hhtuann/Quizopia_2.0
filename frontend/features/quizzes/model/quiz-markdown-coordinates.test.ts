import { describe, expect, it } from "vitest";
import {
  normalizeQuizMarkdownForTextarea,
  preserveQuizMarkdownTextareaEdit,
  quizMarkdownPopupGeometry,
  sourceLocationToOffset,
  sourceOffsetFromTextareaOffset,
  sourceOffsetToLocation,
  sourceOffsetToVisualLocation,
  textareaOffsetFromSourceOffset,
} from "./quiz-markdown-coordinates";

describe("Quiz Markdown coordinate model", () => {
  it.each(["first\nmiddle\nlast", "first\r\nmiddle\r\nlast"])(
    "round-trips line and column against exact source offsets for %j",
    (source) => {
      for (const token of ["first", "middle", "last"]) {
        const offset = source.indexOf(token) + 2;
        const location = sourceOffsetToLocation(source, offset);
        expect(
          sourceLocationToOffset(source, location.line, location.column),
        ).toBe(offset);
      }
    },
  );

  it("maps textarea LF offsets to authoritative CRLF source offsets", () => {
    const source = "one\r\n\r\n\ttwo";
    const normalized = normalizeQuizMarkdownForTextarea(source);
    const textareaOffset = normalized.indexOf("two");
    const sourceOffset = source.indexOf("two");

    expect(sourceOffsetFromTextareaOffset(source, textareaOffset)).toBe(
      sourceOffset,
    );
    expect(textareaOffsetFromSourceOffset(source, sourceOffset)).toBe(
      textareaOffset,
    );
    expect(sourceOffsetToLocation(source, sourceOffset)).toEqual({
      column: 2,
      line: 3,
    });
  });

  it("expands literal tabs deterministically for visual columns only", () => {
    const source = "\tA\tB";
    expect(sourceOffsetToVisualLocation(source, source.length)).toEqual({
      column: 5,
      line: 1,
      visualColumn: 9,
    });
    expect(source).toBe("\tA\tB");
  });

  it("clamps missing lines and oversized columns to real source content", () => {
    const source = "one\r\ntwo";
    expect(sourceLocationToOffset(source, 99, 99)).toBe(source.length);
    expect(sourceLocationToOffset(source, 1, 99)).toBe(3);
  });

  it("preserves exact CRLF while applying a textarea-normalized edit", () => {
    expect(preserveQuizMarkdownTextareaEdit("one\r\ntwo", "one\n\ttwo")).toBe(
      "one\r\n\ttwo",
    );
  });

  it("anchors below the caret and clamps horizontally", () => {
    expect(
      quizMarkdownPopupGeometry({
        caretLine: 5,
        caretVisualColumn: 80,
        characterWidth: 8,
        gutterWidth: 48,
        lineHeight: 24,
        paddingLeft: 16,
        paddingTop: 12,
        popupHeight: 100,
        popupWidth: 240,
        scrollLeft: 120,
        scrollTop: 72,
        viewportHeight: 400,
        viewportWidth: 500,
      }),
    ).toEqual({ left: 256, placement: "below", top: 64 });
  });

  it("flips above near the visible bottom and stays in bounds", () => {
    const geometry = quizMarkdownPopupGeometry({
      caretLine: 12,
      caretVisualColumn: 2,
      characterWidth: 8,
      gutterWidth: 48,
      lineHeight: 24,
      paddingLeft: 16,
      paddingTop: 12,
      popupHeight: 120,
      popupWidth: 240,
      scrollLeft: 0,
      scrollTop: 60,
      viewportHeight: 240,
      viewportWidth: 500,
    });
    expect(geometry).toEqual({ left: 80, placement: "above", top: 92 });
  });
});
