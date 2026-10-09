import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { QuizPreview } from "./quiz-preview";

describe("QuizPreview", () => {
  it("renders inline and display LaTeX in questions, answers, and explanations", () => {
    const { container } = render(
      <QuizPreview
        source={
          "C\u00e2u 1 [SINGLE_CHOICE]: Solve $x^2 + 1$\n$$\\frac{1}{2}$$\n*A. $\\sqrt{4}$\nB. $y_1$\nC. 3\nD. 4\nL\u1eddi gi\u1ea3i: Integral\n$$\\int_0^1 x\\,dx$$"
        }
      />,
    );
    expect(container.querySelectorAll(".katex").length).toBeGreaterThanOrEqual(
      4,
    );
    expect(container.querySelectorAll(".katex-display").length).toBe(2);
    expect(
      screen.getByRole("group", { name: "A. Marked correct" }),
    ).toBeInTheDocument();
  });

  it("keeps code spans, code fences, currency, and malformed math literal", () => {
    const { container } = render(
      <QuizPreview
        source={
          "C\u00e2u 1 [SINGLE_CHOICE]: `$x^2$` costs $5 and $10. Unknown $\\notarealcommand{a}$\n```\n$$x^2$$\n```\n*A. $a+b$\nB. b\nC. c\nD. d"
        }
      />,
    );
    expect(container.querySelector("p code")).toHaveTextContent("$x^2$");
    expect(container.querySelector("pre code")).toHaveTextContent("$$x^2$$");
    expect(container).toHaveTextContent("$5 and $10");
    expect(container).toHaveTextContent("$\\notarealcommand{a}$");
    expect(container.querySelectorAll(".katex")).toHaveLength(1);
  });

  it("renders unsafe HTML commands as literal source and preserves read-only history", () => {
    const { container } = render(
      <QuizPreview
        readOnly
        source={
          "C\u00e2u 1 [NUMERIC_FILL]: $\\htmlClass{evil}{x}$ plus $x+1$\n\u0110\u00e1p \u00e1n: 2.50"
        }
      />,
    );
    expect(container).toHaveTextContent("$\\htmlClass{evil}{x}$");
    expect(container.querySelectorAll(".katex")).toHaveLength(1);
    expect(screen.queryByRole("button", { name: /Jump to source/ })).toBeNull();
  });
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

  it("exposes accessible selected options and source-linked interactions", () => {
    const onOptionToggle = vi.fn();
    const onQuestionSelect = vi.fn();
    render(
      <QuizPreview
        onOptionToggle={onOptionToggle}
        onQuestionSelect={onQuestionSelect}
        source={
          "Câu 1 [SINGLE_CHOICE]: first\nline two\n*A. a\nB. b\nC. c\nD. d"
        }
      />,
    );

    const selected = screen.getByRole("button", { name: "A. Marked correct" });
    const unselected = screen.getByRole("button", {
      name: "B. Not marked correct",
    });
    expect(selected).toHaveAttribute("aria-pressed", "true");
    expect(unselected).toHaveAttribute("aria-pressed", "false");
    expect(screen.queryByText("Correct")).not.toBeInTheDocument();

    fireEvent.click(
      screen.getByRole("button", { name: /Jump to source for question 1/ }),
    );
    expect(onQuestionSelect.mock.calls[0]?.[0].source).toEqual({
      line: 1,
      offset: 0,
    });
    fireEvent.click(unselected);
    expect(onOptionToggle.mock.calls[0]?.[1]).toMatchObject({
      label: "B",
      source: { line: 4 },
    });
  });

  it("renders a historical snapshot without mutation or source-navigation controls", () => {
    render(
      <QuizPreview
        description="Immutable snapshot"
        readOnly
        source={
          "Câu 1 [SINGLE_CHOICE]: historical question\n*A. a\nB. b\nC. c\nD. d"
        }
        title="Published preview · Version 1"
      />,
    );

    expect(
      screen.getByRole("heading", { name: "Published preview · Version 1" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Immutable snapshot")).toBeInTheDocument();
    expect(
      screen.getByRole("group", { name: "A. Marked correct" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Jump to source/ }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "B. Not marked correct" }),
    ).not.toBeInTheDocument();
  });
});
