import { readFileSync } from "node:fs";
import { join } from "node:path";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { QUIZOPIA_BOLT_PATH } from "./quizopia-brand-geometry";
import { QuizopiaLogo } from "./quizopia-logo";

describe("QuizopiaLogo", () => {
  it("renders the reusable lightning mark and two-line brand wordmark", () => {
    const { container } = render(<QuizopiaLogo />);

    expect(screen.getByTestId("quizopia-brand-mark")).toBeInTheDocument();
    expect(container.querySelector("path")).toHaveAttribute(
      "d",
      QUIZOPIA_BOLT_PATH,
    );
    expect(screen.getByText("Quiz").parentElement).toHaveClass("font-heading");
    expect(screen.getByText("opia")).toHaveClass(
      "bg-gradient-to-r",
      "from-primary",
      "to-secondary",
    );
    expect(screen.getByText("version 2.0")).toHaveClass(
      "font-sans",
      "mt-0.5",
      "leading-none",
    );
    expect(screen.queryByText("Q")).not.toBeInTheDocument();
  });

  it("uses the canonical UI bolt silhouette in the favicon", () => {
    const favicon = readFileSync(
      join(process.cwd(), "app", "icon.svg"),
      "utf8",
    );
    expect(favicon).toContain(`d="${QUIZOPIA_BOLT_PATH}"`);
    expect(favicon).toContain("#4F46E5");
    expect(favicon).toContain("#7C3AED");
  });
});
