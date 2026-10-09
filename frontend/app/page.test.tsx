import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import HomePage from "./page";

const mockSession = vi.hoisted(() => ({ status: "anonymous" }));

vi.mock("../features/auth/auth-provider", () => ({
  useAuth: () => ({ session: mockSession }),
}));

beforeEach(() => {
  mockSession.status = "anonymous";
});

describe("public Quizopia landing page", () => {
  it("introduces the learning platform with an accessible main heading", () => {
    render(<HomePage />);

    expect(
      screen.getByRole("heading", {
        level: 1,
        name: "A smarter space to learn and teach.",
      }),
    ).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveAttribute("id", "main-content");
    expect(
      screen.getByRole("navigation", { name: "Public navigation" }),
    ).toBeInTheDocument();
  });

  it("links the public entry points to real authentication routes", () => {
    render(<HomePage />);

    expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute(
      "href",
      "/login",
    );
    expect(
      screen.getByRole("link", { name: "Create your account" }),
    ).toHaveAttribute("href", "/register");
    expect(
      screen.getByRole("link", { name: "Open your workspace" }),
    ).toHaveAttribute("href", "/login");
  });

  it("links an authenticated visitor directly to the application", () => {
    mockSession.status = "authenticated";
    render(<HomePage />);

    expect(
      screen.getByRole("link", { name: "Open workspace" }),
    ).toHaveAttribute("href", "/app");
    expect(
      screen.getByRole("link", { name: "Continue to your workspace" }),
    ).toHaveAttribute("href", "/app");
    expect(screen.queryByRole("link", { name: "Sign in" })).toBeNull();
  });
});
