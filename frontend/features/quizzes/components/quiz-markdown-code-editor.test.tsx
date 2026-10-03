import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { QuizMarkdownCodeEditor } from "./quiz-markdown-code-editor";

function EditorHarness({ initial }: { readonly initial: string }) {
  const [value, setValue] = useState(initial);
  return <QuizMarkdownCodeEditor onChange={setValue} value={value} />;
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
    expect(layer.querySelectorAll(".font-semibold").length).toBeGreaterThan(3);
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
});
