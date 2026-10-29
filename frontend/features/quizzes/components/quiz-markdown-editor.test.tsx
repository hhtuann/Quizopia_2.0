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

function ChangeRecordingHarness({ initial }: { readonly initial: string }) {
  const [value, setValue] = useState(initial);
  const [changes, setChanges] = useState<string[]>([]);
  return (
    <>
      <output data-testid="numeric-change-log">{changes.join("\u0001")}</output>
      <QuizMarkdownEditor
        onChange={(next) => {
          setValue(next);
          setChanges((current) => [...current, next]);
        }}
        value={value}
      />
    </>
  );
}

describe("QuizMarkdownEditor NUMERIC_FILL answer marker autocomplete", () => {
  const numericQuestion =
    "Câu 2 [NUMERIC_FILL]: Giá trị của 10 / 4 là bao nhiêu?";

  it("offers exactly the canonical Đáp án: marker on an empty line after a NUMERIC_FILL question", () => {
    render(<EditorHarness initial={numericQuestion} />);
    typeAtEnd(`${numericQuestion}\n`);
    const options = screen.getAllByRole("option");
    expect(options).toHaveLength(1);
    expect(options[0]).toHaveTextContent(/^Đáp án:$/);
  });

  it("keeps filtering progressively while typing the marker with diacritics", () => {
    render(<EditorHarness initial={numericQuestion} />);
    for (const prefix of ["Đ", "Đá", "đáp á", "Đáp án:"]) {
      typeAtEnd(`${numericQuestion}\n${prefix}`);
      const options = screen.getAllByRole("option");
      expect(options).toHaveLength(1);
      expect(options[0]).toHaveTextContent(/^Đáp án:$/);
    }
  });

  it("inserts the byte-exact marker with a trailing space at the caret and leaves authored text untouched", () => {
    const source = `Câu 1 [SINGLE_CHOICE]: 2 + 2 bằng bao nhiêu?\n\nA. 3\n*B. 4\nC. 5\nD. 6\n${numericQuestion}\n`;
    render(<EditorHarness initial={source.slice(0, -1)} />);
    const editor = typeAtEnd(source);
    fireEvent.keyDown(editor, { key: "Enter" });

    const expected = `${source}Đáp án: `;
    expect(editor).toHaveValue(expected);
    expect(editor.selectionStart).toBe(expected.length);
    expect(editor.selectionEnd).toBe(expected.length);
  });

  it("accepts the marker with ArrowDown/ArrowUp navigation and Enter", () => {
    render(<EditorHarness initial={numericQuestion} />);
    const editor = typeAtEnd(`${numericQuestion}\n`);
    fireEvent.keyDown(editor, { key: "ArrowDown" });
    expect(screen.getAllByRole("option")[0]).toHaveAttribute(
      "aria-selected",
      "true",
    );
    fireEvent.keyDown(editor, { key: "ArrowUp" });
    fireEvent.keyDown(editor, { key: "Enter" });
    expect(editor).toHaveValue(`${numericQuestion}\nĐáp án: `);
  });

  it("dismisses the marker suggestion with Escape without changing source", () => {
    render(<EditorHarness initial={numericQuestion} />);
    const editor = typeAtEnd(`${numericQuestion}\n`);
    expect(screen.getAllByRole("option")).toHaveLength(1);
    fireEvent.keyDown(editor, { key: "Escape" });
    expect(screen.queryAllByRole("option")).toHaveLength(0);
    expect(editor).toHaveValue(`${numericQuestion}\n`);
  });

  it("does not offer the marker again once the question already has an answer line", () => {
    render(<EditorHarness initial={numericQuestion} />);
    typeAtEnd(`${numericQuestion}\nĐáp án: 2.50\n`);
    expect(screen.queryAllByRole("option")).toHaveLength(0);
  });

  it("does not offer the marker before the NUMERIC_FILL stem has content", () => {
    render(<EditorHarness initial="Câu 2 [NUMERIC_FILL]:" />);
    typeAtEnd("Câu 2 [NUMERIC_FILL]:\n");
    expect(screen.queryAllByRole("option")).toHaveLength(0);
  });

  it.each([
    [
      "SINGLE_CHOICE options context",
      "Câu 1 [SINGLE_CHOICE]: choose\n",
      ["A.", "*A."],
    ],
    [
      "completed MULTIPLE_CHOICE structure",
      "Câu 1 [MULTIPLE_CHOICE]: choose\nA. a\nB. b\nC. c\nD. d\n",
      [],
    ],
    ["ordinary prose", "ordinary prose\n", []],
  ] as const)(
    "does not offer the marker inside %s",
    (_label, source, expectedOptions) => {
      render(<EditorHarness initial={source.slice(0, -1)} />);
      typeAtEnd(source);
      expect(
        screen.queryAllByRole("option").map((option) => option.textContent),
      ).toEqual(expectedOptions);
    },
  );

  it("applies the marker as one discrete edit through the shared completion path", () => {
    const typed = `${numericQuestion}\n`;
    render(<ChangeRecordingHarness initial={numericQuestion} />);
    const editor = typeAtEnd(typed);
    fireEvent.keyDown(editor, { key: "Enter" });

    expect(screen.getByTestId("numeric-change-log").textContent).toBe(
      [typed, `${typed}Đáp án: `].join("\u0001"),
    );
  });
});

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
