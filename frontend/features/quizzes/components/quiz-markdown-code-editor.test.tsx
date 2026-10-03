import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useRef, useState } from "react";
import { describe, expect, it } from "vitest";
import { QuizMarkdownCodeEditor } from "./quiz-markdown-code-editor";
import type { QuizMarkdownCodeEditorHandle } from "./quiz-markdown-code-editor";

function EditorHarness({ initial }: { readonly initial: string }) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <output data-testid="exact-editor-value">{value}</output>
      <QuizMarkdownCodeEditor onChange={setValue} value={value} />
    </>
  );
}

function ProgrammaticFocusHarness({
  focusOffset,
  initial,
}: {
  readonly focusOffset?: number;
  readonly initial: string;
}) {
  const [value, setValue] = useState(initial);
  const editorRef = useRef<QuizMarkdownCodeEditorHandle>(null);
  return (
    <>
      <button
        onClick={() =>
          editorRef.current?.focusOffset(focusOffset ?? value.length, {
            suppressAutocomplete: true,
          })
        }
        type="button"
      >
        Focus without completion
      </button>
      <QuizMarkdownCodeEditor
        onChange={setValue}
        ref={editorRef}
        value={value}
      />
    </>
  );
}

describe("QuizMarkdownCodeEditor", () => {
  it("renders synchronized line numbers and updates the active caret line", () => {
    render(<EditorHarness initial={"first\nsecond\nthird"} />);
    const editor = screen.getByLabelText("Quiz Markdown source");

    expect(screen.getByTestId("quiz-markdown-line-numbers")).toHaveTextContent(
      "123",
    );
    fireEvent.select(editor, {
      target: { selectionStart: 7, selectionEnd: 7 },
    });
    expect(screen.getByTestId("quiz-markdown-active-line")).toHaveStyle({
      top: "36px",
    });
  });

  it("highlights structural tokens while keeping the textarea source exact", () => {
    const source =
      "Câu 1 [SINGLE_CHOICE]: stem\n*A. correct\nB. other\nC. third\nD. fourth\nLời giải: detail";
    render(<EditorHarness initial={source} />);

    const editor = screen.getByLabelText<HTMLTextAreaElement>(
      "Quiz Markdown source",
    );
    const layer = screen.getByTestId("quiz-markdown-highlight-layer");
    expect(editor.value).toBe(source);
    expect(
      layer.querySelectorAll(".text-primary, .text-secondary, .text-warning")
        .length,
    ).toBeGreaterThan(3);
    expect(layer.querySelectorAll(".font-semibold, .font-bold")).toHaveLength(
      0,
    );
    expect(layer).toHaveTextContent("SINGLE_CHOICE");
    expect(layer).toHaveTextContent("Lời giải:");
  });

  it("does not highlight structural-looking fenced code as quiz structure", () => {
    render(
      <EditorHarness
        initial={
          "Câu 1 [SINGLE_CHOICE]: code\n```text\n*A. fake\n```\n*A. real"
        }
      />,
    );
    const layer = screen.getByTestId("quiz-markdown-highlight-layer");
    const fakeLine = Array.from(layer.querySelectorAll("span")).find(
      (node) => node.textContent === "*A. fake",
    );
    expect(fakeLine).toHaveClass("text-foreground-secondary");
  });

  it("accepts lowercase completion with keyboard and inserts canonical syntax", () => {
    render(<EditorHarness initial="câu" />);
    const editor = screen.getByLabelText<HTMLTextAreaElement>(
      "Quiz Markdown source",
    );
    fireEvent.select(editor, {
      target: { selectionStart: 3, selectionEnd: 3 },
    });
    fireEvent.keyDown(editor, { key: "ArrowDown" });
    fireEvent.keyUp(editor, { key: "ArrowDown" });
    fireEvent.keyDown(editor, { key: "Enter" });

    expect(editor.value).toBe("Câu 1 [MULTIPLE_CHOICE]: ");
    expect(editor.selectionStart).toBe(editor.value.length);
  });

  it("accepts an active completion with Tab", () => {
    render(<EditorHarness initial="câu" />);
    const editor = screen.getByLabelText<HTMLTextAreaElement>(
      "Quiz Markdown source",
    );
    fireEvent.select(editor, {
      target: { selectionStart: 3, selectionEnd: 3 },
    });
    fireEvent.keyDown(editor, { key: "Tab" });

    expect(editor.value).toBe("Câu 1 [SINGLE_CHOICE]: ");
    expect(editor.selectionStart).toBe(editor.value.length);
  });

  it("inserts an exact tab at the caret and retains editor focus", async () => {
    render(<EditorHarness initial="alpha" />);
    const editor = screen.getByLabelText<HTMLTextAreaElement>(
      "Quiz Markdown source",
    );
    editor.focus();
    editor.setSelectionRange(2, 2);
    fireEvent.keyDown(editor, { key: "Tab" });

    expect(editor.value).toBe("al\tpha");
    await waitFor(() => expect(editor.selectionStart).toBe(3));
    expect(editor.selectionEnd).toBe(3);
    expect(editor).toHaveFocus();
  });

  it("preserves a literal tab authored inside fenced code", async () => {
    const source = "```text\nvalue\n```";
    render(<EditorHarness initial={source} />);
    const editor = screen.getByLabelText<HTMLTextAreaElement>(
      "Quiz Markdown source",
    );
    const caret = source.indexOf("value");
    editor.focus();
    editor.setSelectionRange(caret, caret);
    fireEvent.keyDown(editor, { key: "Tab" });

    expect(editor.value).toBe("```text\n\tvalue\n```");
    await waitFor(() => expect(editor.selectionStart).toBe(caret + 1));
    expect(editor).toHaveFocus();
  });

  it("indents and outdents selected lines without changing CRLF endings", async () => {
    const source = "one\r\ntwo\r\nthree";
    render(<EditorHarness initial={source} />);
    const editor = screen.getByLabelText<HTMLTextAreaElement>(
      "Quiz Markdown source",
    );
    editor.focus();
    editor.setSelectionRange(0, source.length);
    fireEvent.keyDown(editor, { key: "Tab" });

    expect(screen.getByTestId("exact-editor-value").textContent).toBe(
      "\tone\r\n\ttwo\r\n\tthree",
    );
    expect(editor.value).toBe("\tone\n\ttwo\n\tthree");
    await waitFor(() => expect(editor.selectionStart).toBe(1));
    expect(editor.selectionEnd).toBe(source.replace(/\r\n/g, "\n").length + 3);

    fireEvent.keyDown(editor, { key: "Tab", shiftKey: true });
    expect(screen.getByTestId("exact-editor-value").textContent).toBe(source);
    expect(editor.value).toBe(source.replace(/\r\n/g, "\n"));
    await waitFor(() => expect(editor.selectionStart).toBe(0));
    expect(editor.selectionEnd).toBe(source.replace(/\r\n/g, "\n").length);
  });

  it("does not remove authored text when Shift+Tab has no leading tab", () => {
    render(<EditorHarness initial="alpha" />);
    const editor = screen.getByLabelText<HTMLTextAreaElement>(
      "Quiz Markdown source",
    );
    editor.focus();
    editor.setSelectionRange(3, 3);
    fireEvent.keyDown(editor, { key: "Tab", shiftKey: true });

    expect(editor.value).toBe("alpha");
    expect(editor).toHaveFocus();
  });

  it("suppresses completion for programmatic navigation until real typing", () => {
    render(<ProgrammaticFocusHarness initial="câu" />);
    const editor = screen.getByLabelText<HTMLTextAreaElement>(
      "Quiz Markdown source",
    );

    fireEvent.click(
      screen.getByRole("button", { name: "Focus without completion" }),
    );
    expect(
      screen.queryByRole("listbox", { name: "Quiz Markdown suggestions" }),
    ).not.toBeInTheDocument();

    fireEvent.change(editor, {
      target: { selectionStart: 2, selectionEnd: 2, value: "câ" },
    });
    expect(
      screen.getByRole("listbox", { name: "Quiz Markdown suggestions" }),
    ).toBeInTheDocument();
  });

  it("restores marker replacement suggestions after a real manual click", () => {
    const source =
      "Câu 1 [MULTIPLE_CHOICE]: choose\nA. alpha\nB. Văn\nC. gamma\nD. delta";
    const markerCaret = source.indexOf("B. Văn") + 1;
    render(
      <ProgrammaticFocusHarness focusOffset={markerCaret} initial={source} />,
    );
    const editor = screen.getByLabelText<HTMLTextAreaElement>(
      "Quiz Markdown source",
    );

    fireEvent.click(
      screen.getByRole("button", { name: "Focus without completion" }),
    );
    expect(
      screen.queryByRole("listbox", { name: "Quiz Markdown suggestions" }),
    ).not.toBeInTheDocument();

    editor.setSelectionRange(markerCaret, markerCaret);
    fireEvent.click(editor);
    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["B.", "*B."]);
  });

  it("replaces an existing question marker and preserves the stem exactly", async () => {
    const source = "Câu 1 [MULTIPLE_CHOICE]: Stem  with  spaces";
    render(<EditorHarness initial={source} />);
    const editor = screen.getByLabelText<HTMLTextAreaElement>(
      "Quiz Markdown source",
    );
    const caret = source.indexOf("MULTIPLE_CHOICE") + 4;
    editor.setSelectionRange(caret, caret);
    fireEvent.click(editor);
    fireEvent.click(
      screen.getByRole("option", { name: "Câu 1 [SINGLE_CHOICE]:" }),
    );

    const expected = "Câu 1 [SINGLE_CHOICE]: Stem  with  spaces";
    expect(editor.value).toBe(expected);
    await waitFor(() =>
      expect(editor.selectionStart).toBe(expected.indexOf(":") + 1),
    );
    expect(editor.selectionEnd).toBe(editor.selectionStart);
  });

  it.each([
    ["B. Văn", "*B.", "*B. Văn"],
    ["*B. Văn", "B.", "B. Văn"],
  ] as const)(
    "replaces only the existing option marker in %s",
    async (optionLine, selectedMarker, expectedLine) => {
      const prefix = "Câu 1 [MULTIPLE_CHOICE]: choose\nA. alpha\n";
      const source = `${prefix}${optionLine}\nC. gamma\nD. delta`;
      render(<EditorHarness initial={source} />);
      const editor = screen.getByLabelText<HTMLTextAreaElement>(
        "Quiz Markdown source",
      );
      const caret = prefix.length + (optionLine.startsWith("*") ? 2 : 1);
      editor.setSelectionRange(caret, caret);
      fireEvent.click(editor);
      fireEvent.click(screen.getByRole("option", { name: selectedMarker }));

      const expected = `${prefix}${expectedLine}\nC. gamma\nD. delta`;
      expect(editor.value).toBe(expected);
      await waitFor(() =>
        expect(editor.selectionStart).toBe(
          prefix.length + selectedMarker.length,
        ),
      );
      expect(editor.selectionEnd).toBe(editor.selectionStart);
    },
  );
});
