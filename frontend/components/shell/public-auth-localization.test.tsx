import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import HomePage from "../../app/page";
import AuthLayout from "../../app/(auth)/layout";
import { AuthPageHeader } from "../../features/auth/components/auth-page-header";
import { I18nProvider } from "../../lib/i18n/provider";

vi.mock("../../features/auth/auth-provider", () => ({
  useAuth: () => ({ session: { status: "anonymous" } }),
}));

describe("public and authentication localization", () => {
  it("renders Vietnamese public copy by default and switches all public navigation in place", () => {
    render(
      <I18nProvider>
        <HomePage />
      </I18nProvider>,
    );
    expect(
      screen.getByRole("navigation", { name: "Điều hướng công khai" }),
    ).toBeVisible();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      "Không gian thông minh để học tập và giảng dạy.",
    );
    expect(
      screen.getByRole("heading", { name: "Tự tin giảng dạy" }),
    ).toBeVisible();
    expect(screen.getByRole("link", { name: "Đăng nhập" })).toHaveAttribute(
      "href",
      "/login",
    );
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "en" } });
    expect(
      screen.getByRole("navigation", { name: "Public navigation" }),
    ).toBeVisible();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      "A smarter space to learn and teach.",
    );
    expect(
      screen.getByRole("heading", { name: "Teach with confidence" }),
    ).toBeVisible();
    expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute(
      "href",
      "/login",
    );
  });

  it("switches the welcome panel and auth heading without remounting form content", () => {
    render(
      <I18nProvider>
        <AuthLayout>
          <AuthPageHeader
            title="Verify your email"
            description="Enter the six-digit code issued for your username, or request another code."
          />
          <input
            aria-label="user content"
            defaultValue="Learning — Giảng dạy"
          />
        </AuthLayout>
      </I18nProvider>,
    );
    const input = screen.getByRole("textbox");
    expect(
      screen.getByRole("heading", { name: "Xác minh email của bạn" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Dành cho người học")).toBeInTheDocument();
    fireEvent.change(input, { target: { value: "Exact draft text" } });
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "en" } });
    expect(
      screen.getByRole("heading", { name: "Verify your email" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Built for learners")).toBeInTheDocument();
    expect(screen.getByRole("textbox")).toBe(input);
    expect(input).toHaveValue("Exact draft text");
  });
});
