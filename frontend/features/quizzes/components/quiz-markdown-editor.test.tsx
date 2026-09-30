import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { QuizMarkdownEditor } from "./quiz-markdown-editor";

function EditorHarness({ initial = "" }: { readonly initial?: string }) {
  const [value, setValue] = useState(initial);
  return <QuizMarkdownEditor onChange={setValue} value={value} />;
}

function typeAtEnd(value: string) {
  const editor = screen.getByRole("textbox", { name: "Quiz Markdown source" });
  fireEvent.change(editor, {
    target: { selectionEnd: value.length, selectionStart: value.length, value },
  });
  fireEvent.select(editor, {
    target: { selectionEnd: value.length, selectionStart: value.length },
  });
  return editor as HTMLTextAreaElement;
}

describe("QuizMarkdownEditor autocomplete interaction", () => {
  it("navigates suggestions with arrows and accepts with Enter", () => {
    render(<EditorHarness />);
    const editor = typeAtEnd("C");
    const options = screen.getAllByRole("option");
    expect(options).toHaveLength(4);
    expect(options[0]).toHaveAttribute("aria-selected", "true");

    fireEvent.keyDown(editor, { key: "ArrowDown" });
    expect(options[1]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(editor, { key: "Enter" });

    expect(editor).toHaveValue("Câu 1 [MULTIPLE_CHOICE]: ");
    expect(editor.selectionStart).toBe("Câu 1 [MULTIPLE_CHOICE]: ".length);
  });

  it("wraps ArrowUp and accepts the active suggestion with Tab", () => {
    render(<EditorHarness />);
    const editor = typeAtEnd("Câu");
    fireEvent.keyDown(editor, { key: "ArrowUp" });
    expect(screen.getAllByRole("option")[3]).toHaveAttribute(
      "aria-selected",
      "true",
    );
    fireEvent.keyDown(editor, { key: "Tab" });
    expect(editor).toHaveValue("Câu 1 [NUMERIC_FILL]: ");
  });

  it("accepts a suggestion with one mouse click while retaining editor focus", () => {
    render(<EditorHarness />);
    const editor = typeAtEnd("Câ");
    fireEvent.mouseDown(
      screen.getByRole("option", { name: "Câu 1 [TRUE_FALSE_MATRIX]:" }),
    );
    fireEvent.click(
      screen.getByRole("option", { name: "Câu 1 [TRUE_FALSE_MATRIX]:" }),
    );
    expect(editor).toHaveValue("Câu 1 [TRUE_FALSE_MATRIX]: ");
    expect(editor).toHaveFocus();
  });

  it("dismisses suggestions with Escape without changing source", () => {
    render(<EditorHarness />);
    const editor = typeAtEnd("C");
    expect(screen.getAllByRole("option")).toHaveLength(4);
    fireEvent.keyDown(editor, { key: "Escape" });
    expect(screen.queryAllByRole("option")).toHaveLength(0);
    expect(editor).toHaveValue("C");
  });

  it("does not intercept Tab or Enter when completion is inactive", () => {
    render(<EditorHarness initial="ordinary prose" />);
    const editor = screen.getByRole("textbox", {
      name: "Quiz Markdown source",
    });
    expect(fireEvent.keyDown(editor, { key: "Tab" })).toBe(true);
    expect(fireEvent.keyDown(editor, { key: "Enter" })).toBe(true);
  });

  it("exposes listbox, active option and controlling textbox semantics", () => {
    render(<EditorHarness />);
    const editor = typeAtEnd("C");
    const listbox = screen.getByRole("listbox", {
      name: "Quiz Markdown suggestions",
    });
    const active = screen.getAllByRole("option")[0];
    expect(editor).toHaveAttribute("aria-controls", listbox.id);
    expect(editor).toHaveAttribute("aria-autocomplete", "list");
    expect(editor).toHaveAttribute("aria-activedescendant", active.id);
  });
});
