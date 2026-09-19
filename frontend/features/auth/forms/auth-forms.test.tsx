import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../auth-provider";
import { createAccessTokenVault } from "../session/access-token-vault";
import { createSessionRuntime } from "../session/session-runtime";
import { EmailVerificationForm } from "./email-verification-form";
import { LoginForm } from "./login-form";
import { RegistrationForm } from "./registration-form";

afterEach(() => {
  vi.restoreAllMocks();
});

function renderWithSessionBoundary(component: React.ReactNode) {
  const accessTokenVault = createAccessTokenVault();
  const sessionRuntime = createSessionRuntime(accessTokenVault);
  const initialSession = sessionRuntime.getSnapshot();

  render(<AuthProvider runtime={sessionRuntime}>{component}</AuthProvider>);

  return { accessTokenVault, initialSession, sessionRuntime };
}

function expectSessionUnchanged(
  harness: ReturnType<typeof renderWithSessionBoundary>,
) {
  expect(harness.sessionRuntime.getSnapshot()).toBe(harness.initialSession);
  expect(harness.accessTokenVault.read()).toBeNull();
}

describe("login form", () => {
  it("provides visible local-credential labels and browser autocomplete semantics", () => {
    render(<LoginForm />);

    const username = screen.getByLabelText("Username");
    const password = screen.getByLabelText("Password");
    expect(username).toHaveAttribute("type", "text");
    expect(username).toHaveAttribute("autocomplete", "username");
    expect(password).toHaveAttribute("type", "password");
    expect(password).toHaveAttribute("autocomplete", "current-password");
    expect(screen.getByRole("button", { name: "Sign in" })).toHaveAttribute(
      "type",
      "submit",
    );
  });

  it("associates empty validation errors without exposing the password value", async () => {
    render(<LoginForm />);
    const passwordValue = "private-password-value";
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: passwordValue },
    });

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    const usernameError = await screen.findByText("Enter your username.");
    const username = screen.getByLabelText("Username");
    expect(username).toHaveAttribute("aria-invalid", "true");
    expect(username.getAttribute("aria-describedby")).toContain(
      usernameError.id,
    );
    expect(screen.queryByText(passwordValue)).not.toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("reports unavailable integration without network or session mutation", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockRejectedValue(new Error("Unexpected network call"));
    const harness = renderWithSessionBoundary(<LoginForm />);
    fireEvent.change(screen.getByLabelText("Username"), {
      target: { value: "learner" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "password without invented policy" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Sign-in is not available yet",
    );
    expect(screen.getByRole("status")).toHaveTextContent(
      "was not sent or saved",
    );
    expect(fetchSpy).not.toHaveBeenCalled();
    expectSessionUnchanged(harness);
  });
});

describe("registration form", () => {
  it("renders only supported registration fields plus local password confirmation", async () => {
    render(<RegistrationForm />);

    expect(screen.getByLabelText("Username")).toHaveAttribute(
      "autocomplete",
      "username",
    );
    expect(screen.getByLabelText("Email address")).toHaveAttribute(
      "autocomplete",
      "email",
    );
    expect(screen.getByLabelText("Password", { exact: true })).toHaveAttribute(
      "autocomplete",
      "new-password",
    );
    expect(screen.getByLabelText("Confirm password")).toHaveAttribute(
      "autocomplete",
      "new-password",
    );

    fireEvent.click(screen.getByRole("button", { name: "Create account" }));
    expect(await screen.findByText("Enter a username.")).toBeInTheDocument();
    expect(screen.getByText("Enter an email address.")).toBeInTheDocument();
    expect(screen.getByText("Enter a password.")).toBeInTheDocument();
    expect(screen.getByText("Confirm your password.")).toBeInTheDocument();
  });

  it("accepts a syntactic non-Gmail email and nonblank password without invented complexity", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockRejectedValue(new Error("Unexpected network call"));
    const harness = renderWithSessionBoundary(<RegistrationForm />);
    fireEvent.change(screen.getByLabelText("Username"), {
      target: { value: "learner" },
    });
    fireEvent.change(screen.getByLabelText("Email address"), {
      target: { value: "learner@school.edu" },
    });
    fireEvent.change(screen.getByLabelText("Password", { exact: true }), {
      target: { value: "x" },
    });
    fireEvent.change(screen.getByLabelText("Confirm password"), {
      target: { value: "x" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Account creation is not available yet",
    );
    expect(
      screen.queryByText("Enter a valid email address."),
    ).not.toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
    expectSessionUnchanged(harness);
  });

  it("keeps password confirmation as an associated local validation error", async () => {
    render(<RegistrationForm />);
    fireEvent.change(screen.getByLabelText("Username"), {
      target: { value: "learner" },
    });
    fireEvent.change(screen.getByLabelText("Email address"), {
      target: { value: "learner@example.com" },
    });
    fireEvent.change(screen.getByLabelText("Password", { exact: true }), {
      target: { value: "first password" },
    });
    fireEvent.change(screen.getByLabelText("Confirm password"), {
      target: { value: "different password" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    const mismatch = await screen.findByText("Passwords do not match.");
    const confirmation = screen.getByLabelText("Confirm password");
    expect(confirmation).toHaveAttribute("aria-invalid", "true");
    expect(confirmation.getAttribute("aria-describedby")).toContain(
      mismatch.id,
    );
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});

describe("email verification form", () => {
  it("requires only nonblank code material and exposes no invented OTP policy", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockRejectedValue(new Error("Unexpected network call"));
    const harness = renderWithSessionBoundary(<EmailVerificationForm />);
    const code = screen.getByLabelText("Verification code");
    expect(code).toHaveAttribute("type", "text");
    expect(code).toHaveAttribute("autocomplete", "one-time-code");
    expect(code).not.toHaveAttribute("inputmode", "numeric");
    expect(code).not.toHaveAttribute("maxlength");

    fireEvent.click(screen.getByRole("button", { name: "Verify email" }));
    const requiredError = await screen.findByText(
      "Enter your verification code.",
    );
    expect(code).toHaveAttribute("aria-invalid", "true");
    expect(code.getAttribute("aria-describedby")).toContain(requiredError.id);

    fireEvent.change(code, { target: { value: "alpha / ?" } });
    fireEvent.click(screen.getByRole("button", { name: "Verify email" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Email verification is not available yet",
    );
    expect(screen.getByRole("status")).not.toHaveTextContent(
      /success|verified/i,
    );
    expect(document.body).not.toHaveTextContent(/seconds|attempts? remaining/i);
    expect(fetchSpy).not.toHaveBeenCalled();
    expectSessionUnchanged(harness);
  });
});
