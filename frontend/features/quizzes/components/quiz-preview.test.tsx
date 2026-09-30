import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { QuizPreview } from "./quiz-preview";

describe("QuizPreview", () => {
  it("renders the accepted inline Markdown subset without executing raw HTML or links", () => {
    const { container } = render(
      <QuizPreview
        source={
          "Câu 1 [SINGLE_CHOICE]: **bold** *italic* `code` <img src=x onerror=alert(1)> [link](https://example.com)\n*A. a\nB. b\nC. c\nD. d"
        }
      />,
    );

    expect(
      screen.getByText("bold", { selector: "strong" }),
    ).toBeInTheDocument();
    expect(screen.getByText("italic", { selector: "em" })).toBeInTheDocument();
    expect(screen.getByText("code", { selector: "code" })).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("a")).toBeNull();
    expect(container).toHaveTextContent("<img src=x onerror=alert(1)>");
    expect(container).toHaveTextContent("[link](https://example.com)");
  });

  it("renders fenced code as inert code and does not parse structural lookalikes inside it", () => {
    const { container } = render(
      <QuizPreview
        source={
          "Câu 1 [SINGLE_CHOICE]: inspect\n```java\nCâu 99 [NUMERIC_FILL]: fake\n*A. fake\n```\n*A. real\nB. b\nC. c\nD. d"
        }
      />,
    );
    expect(screen.getByRole("heading", { name: "Câu 1" })).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "Câu 99" }),
    ).not.toBeInTheDocument();
    expect(container.querySelector("pre code")).toHaveTextContent(
      "Câu 99 [NUMERIC_FILL]: fake",
    );
    expect(container.querySelector("pre code")).not.toHaveTextContent("java");
  });

  it("renders multiline explanations for NUMERIC_FILL", () => {
    render(
      <QuizPreview
        source={
          "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50\nLời giải: first line\nsecond line"
        }
      />,
    );
    expect(screen.getByText("Lời giải")).toBeInTheDocument();
    expect(screen.getByText(/first line/)).toHaveTextContent("second line");
  });
});
