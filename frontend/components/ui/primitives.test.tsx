import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Alert } from "./alert";
import { Button } from "./button";
import { SkipLink } from "./skip-link";
import { TextField } from "./text-field";

describe("product UI primitives", () => {
  it("uses safe button defaults and prevents interaction while loading", () => {
    const { rerender } = render(<Button>Continue</Button>);
    const button = screen.getByRole("button", { name: "Continue" });

    expect(button).toHaveAttribute("type", "button");
    expect(button).toBeEnabled();

    rerender(
      <Button isLoading loadingLabel="Saving changes">
        Continue
      </Button>,
    );

    expect(
      screen.getByRole("button", { name: "Saving changes" }),
    ).toBeDisabled();
    expect(screen.getByRole("button")).toHaveAttribute("aria-busy", "true");
  });

  it("associates a visible field label, help, and error message", () => {
    render(
      <TextField
        error="Enter a valid value."
        helperText="Use a descriptive value."
        label="Display name"
      />,
    );

    const input = screen.getByLabelText("Display name");
    const helper = screen.getByText("Use a descriptive value.");
    const error = screen.getByRole("alert");

    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAttribute(
      "aria-describedby",
      `${helper.id} ${error.id}`,
    );
    expect(error).toHaveTextContent("Enter a valid value.");
  });

  it("uses assertive semantics for danger and polite semantics for status", () => {
    const { rerender } = render(
      <Alert title="Saved" variant="success">
        Your changes are available.
      </Alert>,
    );

    expect(screen.getByRole("status")).toHaveTextContent("Saved");

    rerender(
      <Alert title="Could not save" variant="danger">
        Try again.
      </Alert>,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Could not save");
  });

  it("links keyboard users to the main content target", () => {
    render(
      <>
        <SkipLink href="#main-content">Skip to main content</SkipLink>
        <main id="main-content">Content</main>
      </>,
    );

    expect(
      screen.getByRole("link", { name: "Skip to main content" }),
    ).toHaveAttribute("href", "#main-content");
    expect(document.querySelector("#main-content")).toBeInTheDocument();
  });
});
