import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { Answer, Question } from "../api/attempt-contract.generated";
import { AnswerField } from "./answer-fields";

function renderField(question: Question, answer: Answer) {
  const onChange = vi.fn();
  render(
    <AnswerField
      answer={answer}
      disabled={false}
      onChange={onChange}
      question={question}
    />,
  );
  return onChange;
}

describe("Student answer fields", () => {
  it("renders and clears SINGLE_CHOICE with keyboard-native radios", () => {
    const onChange = renderField(
      {
        number: 1,
        type: "SINGLE_CHOICE",
        stemMarkdown: "Choose",
        options: [
          { label: "A", markdown: "Alpha" },
          { label: "B", markdown: "Beta" },
        ],
      },
      { type: "SINGLE_CHOICE", optionLabel: "A" },
    );
    fireEvent.click(screen.getByRole("radio", { name: /B Beta/ }));
    expect(onChange).toHaveBeenLastCalledWith({
      type: "SINGLE_CHOICE",
      optionLabel: "B",
    });
    fireEvent.click(screen.getByRole("button", { name: "Clear answer" }));
    expect(onChange).toHaveBeenLastCalledWith({
      type: "SINGLE_CHOICE",
      optionLabel: null,
    });
  });

  it("renders MULTIPLE_CHOICE and emits the full sorted set", () => {
    const onChange = renderField(
      {
        number: 2,
        type: "MULTIPLE_CHOICE",
        stemMarkdown: "Choose all",
        options: [
          { label: "A", markdown: "Alpha" },
          { label: "C", markdown: "Gamma" },
        ],
      },
      { type: "MULTIPLE_CHOICE", optionLabels: ["C"] },
    );
    fireEvent.click(screen.getByRole("checkbox", { name: /A Alpha/ }));
    expect(onChange).toHaveBeenCalledWith({
      type: "MULTIPLE_CHOICE",
      optionLabels: ["A", "C"],
    });
  });

  it("preserves all four TRUE_FALSE_MATRIX judgments including explicit null", () => {
    const onChange = renderField(
      {
        number: 3,
        type: "TRUE_FALSE_MATRIX",
        stemMarkdown: "Judge",
        options: [
          { label: "A", markdown: "One" },
          { label: "B", markdown: "Two" },
          { label: "C", markdown: "Three" },
          { label: "D", markdown: "Four" },
        ],
      },
      {
        type: "TRUE_FALSE_MATRIX",
        judgments: { A: null, B: null, C: null, D: null },
      },
    );
    const statement = screen.getByText("One").closest("fieldset")!;
    fireEvent.click(within(statement).getByRole("radio", { name: "True" }));
    expect(onChange).toHaveBeenCalledWith({
      type: "TRUE_FALSE_MATRIX",
      judgments: { A: true, B: null, C: null, D: null },
    });
    expect(screen.getAllByRole("radio")).toHaveLength(12);
  });

  it("keeps NUMERIC_FILL as a string", () => {
    const onChange = renderField(
      { number: 4, type: "NUMERIC_FILL", stemMarkdown: "Value", options: [] },
      { type: "NUMERIC_FILL", value: null },
    );
    const input = screen.getByLabelText("Four-character numeric answer");
    fireEvent.change(input, { target: { value: "0001" } });
    expect(onChange).toHaveBeenLastCalledWith({
      type: "NUMERIC_FILL",
      value: "0001",
    });
  });

  it("emits blank NUMERIC_FILL editing as an explicit empty string", () => {
    const onChange = renderField(
      { number: 4, type: "NUMERIC_FILL", stemMarkdown: "Value", options: [] },
      { type: "NUMERIC_FILL", value: "0001" },
    );
    const input = screen.getByLabelText("Four-character numeric answer");
    fireEvent.change(input, { target: { value: "" } });
    expect(onChange).toHaveBeenLastCalledWith({
      type: "NUMERIC_FILL",
      value: "",
    });
  });
});
