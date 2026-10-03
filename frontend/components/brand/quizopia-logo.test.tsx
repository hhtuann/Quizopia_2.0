import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { QuizopiaLogo } from "./quizopia-logo";

describe("QuizopiaLogo", () => {
  it("renders the reusable lightning mark and two-line brand wordmark", () => {
    render(<QuizopiaLogo />);

    expect(screen.getByTestId("quizopia-brand-mark")).toBeInTheDocument();
    expect(screen.getByText("Quiz").parentElement).toHaveClass("font-heading");
    expect(screen.getByText("opia")).toHaveClass(
      "bg-gradient-to-r",
      "from-primary",
      "to-secondary",
    );
    expect(screen.getByText("version 2.0")).toHaveClass("font-sans");
    expect(screen.queryByText("Q")).not.toBeInTheDocument();
  });
});
