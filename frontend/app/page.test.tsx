import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import HomePage from "./page";

describe("product foundation page", () => {
  it("renders the neutral scaffold with accessible product primitives", () => {
    render(<HomePage />);

    expect(
      screen.getByRole("heading", { name: "Product interface foundation" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Example field")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Design tokens and application providers are ready for review.",
    );
  });

  it("announces the interactive foundation check", () => {
    render(<HomePage />);

    fireEvent.click(screen.getByRole("button", { name: "Check foundation" }));

    expect(screen.getByRole("status")).toHaveTextContent(
      "Interaction, focus, and status feedback are working.",
    );
    expect(screen.getByRole("button", { name: "Reset preview" })).toBeEnabled();
  });
});
