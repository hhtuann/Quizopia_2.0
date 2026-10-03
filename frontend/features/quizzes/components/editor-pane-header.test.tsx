import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { EditorPaneHeader } from "./editor-pane-header";

describe("EditorPaneHeader", () => {
  it("shares one balanced title and helper hierarchy across pane variants", () => {
    const { rerender } = render(
      <EditorPaneHeader
        description="Editor guidance"
        descriptionId="editor-help"
        htmlFor="editor"
        title="Quiz Markdown source"
        titleId="editor-title"
      />,
    );
    const editorTitleClasses = screen.getByText(
      "Quiz Markdown source",
    ).className;
    const editorHelpClasses = screen.getByText("Editor guidance").className;

    rerender(
      <EditorPaneHeader
        description="Preview guidance"
        descriptionId="preview-help"
        title="Live preview"
        titleId="preview-title"
      />,
    );
    expect(
      screen.getByRole("heading", { name: "Live preview" }).className,
    ).toBe(editorTitleClasses);
    expect(screen.getByText("Preview guidance").className).toBe(
      editorHelpClasses,
    );
  });
});
