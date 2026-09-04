import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import HomePage from "./page";

describe("development landing page", () => {
  it("renders the scaffold message", () => {
    render(<HomePage />);
    expect(
      screen.getByText("Development scaffold is running."),
    ).toBeInTheDocument();
  });
});
