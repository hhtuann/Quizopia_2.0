import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EmailVerificationForm } from "./email-verification-form";
import { LoginForm } from "./login-form";
import { RegistrationForm } from "./registration-form";

const navigation = vi.hoisted(() => ({
  push: vi.fn(),
  replace: vi.fn(),
}));

const auth = vi.hoisted(() => ({
  confirmVerification: vi.fn(),
  login: vi.fn(),
  register: vi.fn(),
  requestVerification: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => navigation,
}));

vi.mock("../auth-provider", () => ({
  useAuth: () => auth,
}));

const authoritativeUser = {
  email: "learner01@gmail.com",
  id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  roles: ["STUDENT"] as const,
  username: "learner01",
};

function otpDigits() {
  return screen.getAllByRole("textbox", {
    name: /Verification code digit \d of 6/,
  });
}

function pasteOtp(value: string, index = 0) {
  fireEvent.paste(otpDigits()[index]!, {
    clipboardData: { getData: () => value },
  });
}

function apiError(code: string, status: number) {
  return {
    ok: false as const,
    error: {
      kind: "api-error" as const,
      error: {
        code,
        message: "Request failed.",
        path: "/api/auth/test",
        status,
        traceId: null,
      },
    },
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

beforeEach(() => {
  vi.clearAllMocks();
  auth.login.mockResolvedValue({ ok: true, value: authoritativeUser });
  auth.register.mockResolvedValue({
    ok: true,
    value: "VERIFICATION_REQUIRED",
  });
  auth.confirmVerification.mockResolvedValue({ ok: true, value: undefined });
  auth.requestVerification.mockResolvedValue({
    ok: true,
    value: "VERIFICATION_REQUEST_ACCEPTED",
  });
});

describe("login form", () => {
  it("uses the exact username-or-verified-email identifier contract", async () => {
    render(<LoginForm />);

    const identifier = screen.getByLabelText("Username or email");
    expect(identifier).toHaveAttribute("autocomplete", "username");
    expect(identifier).toHaveAttribute("type", "text");
    expect(screen.getByLabelText("Password")).toHaveAttribute(
      "autocomplete",
      "current-password",
    );

    fireEvent.change(identifier, {
      target: { value: "learner01@gmail.com" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() =>
      expect(auth.login).toHaveBeenCalledWith({
        identifier: "learner01@gmail.com",
        password: "secret",
      }),
    );
    expect(navigation.replace).toHaveBeenCalledWith("/app");
  });

  it("renders the generic invalid-credential error accessibly", async () => {
    auth.login.mockResolvedValueOnce(apiError("AUTH_INVALID_CREDENTIALS", 401));
    render(<LoginForm />);
    fireEvent.change(screen.getByLabelText("Username or email"), {
      target: { value: "learner01" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "wrong" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Sign in failed",
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "username/email or password is incorrect",
    );
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  it("disables duplicate submit while a login is in flight", async () => {
    const pending = deferred<{ ok: true; value: typeof authoritativeUser }>();
    auth.login.mockReturnValueOnce(pending.promise);
    render(<LoginForm />);
    fireEvent.change(screen.getByLabelText("Username or email"), {
      target: { value: "learner01" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "secret" },
    });
    const button = screen.getByRole("button", { name: "Sign in" });

    fireEvent.click(button);
    await waitFor(() => expect(button).toBeDisabled());
    fireEvent.click(button);
    expect(auth.login).toHaveBeenCalledTimes(1);

    pending.resolve({ ok: true, value: authoritativeUser });
    await waitFor(() =>
      expect(navigation.replace).toHaveBeenCalledWith("/app"),
    );
  });
});

describe("registration form", () => {
  it("sends only the real registration DTO and carries username through navigation", async () => {
    render(<RegistrationForm />);
    expect(screen.queryByLabelText(/role/i)).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Username"), {
      target: { value: "learner+01" },
    });
    fireEvent.change(screen.getByLabelText("Email address"), {
      target: { value: "learner01@gmail.com" },
    });
    fireEvent.change(screen.getByLabelText("Password", { exact: true }), {
      target: { value: "secret" },
    });
    fireEvent.change(screen.getByLabelText("Confirm password"), {
      target: { value: "secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() =>
      expect(auth.register).toHaveBeenCalledWith({
        email: "learner01@gmail.com",
        password: "secret",
        username: "learner+01",
      }),
    );
    expect(navigation.push).toHaveBeenCalledWith(
      "/verify-email?username=learner%2B01",
    );
  });

  it("handles username conflicts distinctly", async () => {
    auth.register.mockResolvedValueOnce(
      apiError("AUTH_USERNAME_UNAVAILABLE", 409),
    );
    render(<RegistrationForm />);
    fireEvent.change(screen.getByLabelText("Username"), {
      target: { value: "taken-name" },
    });
    fireEvent.change(screen.getByLabelText("Email address"), {
      target: { value: "learner01@gmail.com" },
    });
    fireEvent.change(screen.getByLabelText("Password", { exact: true }), {
      target: { value: "secret" },
    });
    fireEvent.change(screen.getByLabelText("Confirm password"), {
      target: { value: "secret" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That username is unavailable",
    );
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("prevents duplicate registration submits while the request is in flight", async () => {
    const pending = deferred<{
      ok: true;
      value: "VERIFICATION_REQUIRED";
    }>();
    auth.register.mockReturnValueOnce(pending.promise);
    render(<RegistrationForm />);
    fireEvent.change(screen.getByLabelText("Username"), {
      target: { value: "learner01" },
    });
    fireEvent.change(screen.getByLabelText("Email address"), {
      target: { value: "learner01@gmail.com" },
    });
    fireEvent.change(screen.getByLabelText("Password", { exact: true }), {
      target: { value: "secret" },
    });
    fireEvent.change(screen.getByLabelText("Confirm password"), {
      target: { value: "secret" },
    });
    const button = screen.getByRole("button", { name: "Create account" });

    fireEvent.click(button);
    await waitFor(() => expect(button).toBeDisabled());
    fireEvent.click(button);
    expect(auth.register).toHaveBeenCalledTimes(1);

    pending.resolve({ ok: true, value: "VERIFICATION_REQUIRED" });
    await waitFor(() => expect(navigation.push).toHaveBeenCalledTimes(1));
  });
});

describe("email verification form", () => {
  it("uses username as the subject and enforces a six-digit numeric OTP", async () => {
    render(<EmailVerificationForm initialUsername="learner01" />);
    const username = screen.getByLabelText("Username");
    const code = otpDigits();

    expect(username).toHaveValue("learner01");
    expect(code).toHaveLength(6);
    expect(code[0]).toHaveAttribute("inputmode", "numeric");
    expect(code[0]).toHaveAttribute("autocomplete", "one-time-code");

    fireEvent.change(code[0]!, { target: { value: "abc" } });
    expect(code[0]).toHaveValue("");
    pasteOtp("12ab56");
    expect(code.map((slot) => (slot as HTMLInputElement).value)).toEqual([
      "1",
      "2",
      "5",
      "6",
      "",
      "",
    ]);
    fireEvent.click(screen.getByRole("button", { name: "Verify email" }));

    expect(
      await screen.findByText("Enter the six-digit verification code."),
    ).toBeInTheDocument();
    expect(auth.confirmVerification).not.toHaveBeenCalled();
  });

  it("resends with generic acceptance semantics without revealing account state", async () => {
    render(<EmailVerificationForm initialUsername="learner01" />);
    pasteOtp("12");

    fireEvent.click(screen.getByRole("button", { name: "Resend code" }));

    await waitFor(() =>
      expect(auth.requestVerification).toHaveBeenCalledWith({
        username: "learner01",
      }),
    );
    expect(screen.getByRole("status")).toHaveTextContent(
      "If a verification code can be issued, the request has been accepted.",
    );
    expect((otpDigits()[0] as HTMLInputElement).value).toBe("1");
  });

  it("prevents verify and duplicate resend while a resend request is in flight", async () => {
    const pending = deferred<{
      ok: true;
      value: "VERIFICATION_REQUEST_ACCEPTED";
    }>();
    auth.requestVerification.mockReturnValueOnce(pending.promise);
    render(<EmailVerificationForm initialUsername="learner01" />);
    const resend = screen.getByRole("button", { name: "Resend code" });
    const verify = screen.getByRole("button", { name: "Verify email" });

    fireEvent.click(resend);
    await waitFor(() => {
      expect(resend).toBeDisabled();
      expect(verify).toBeDisabled();
    });
    fireEvent.click(resend);
    expect(auth.requestVerification).toHaveBeenCalledTimes(1);

    pending.resolve({ ok: true, value: "VERIFICATION_REQUEST_ACCEPTED" });
    await waitFor(() => expect(resend).toBeEnabled());
  });

  it("confirms the OTP and directs the user to normal login without establishing a session", async () => {
    render(<EmailVerificationForm initialUsername="learner01" />);
    pasteOtp("123456");

    fireEvent.click(screen.getByRole("button", { name: "Verify email" }));

    await waitFor(() =>
      expect(auth.confirmVerification).toHaveBeenCalledWith({
        otp: "123456",
        username: "learner01",
      }),
    );
    expect(navigation.replace).toHaveBeenCalledWith("/login");
    expect(auth.login).not.toHaveBeenCalled();
  });

  it("prevents duplicate confirmation and resend while verification is in flight", async () => {
    const pending = deferred<{ ok: true; value: undefined }>();
    auth.confirmVerification.mockReturnValueOnce(pending.promise);
    render(<EmailVerificationForm initialUsername="learner01" />);
    pasteOtp("123456");
    const verify = screen.getByRole("button", { name: "Verify email" });
    const resend = screen.getByRole("button", { name: "Resend code" });

    fireEvent.click(verify);
    await waitFor(() => {
      expect(verify).toBeDisabled();
      expect(resend).toBeDisabled();
    });
    fireEvent.click(verify);
    expect(auth.confirmVerification).toHaveBeenCalledTimes(1);

    pending.resolve({ ok: true, value: undefined });
    await waitFor(() =>
      expect(navigation.replace).toHaveBeenCalledWith("/login"),
    );
  });

  it("presents AUTH_VERIFICATION_FAILED generically and accessibly", async () => {
    auth.confirmVerification.mockResolvedValueOnce(
      apiError("AUTH_VERIFICATION_FAILED", 400),
    );
    render(<EmailVerificationForm initialUsername="learner01" />);
    pasteOtp("123456");

    fireEvent.click(screen.getByRole("button", { name: "Verify email" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Email verification failed",
    );
    expect(navigation.replace).not.toHaveBeenCalled();
    expect(
      otpDigits()
        .map((slot) => (slot as HTMLInputElement).value)
        .join(""),
    ).toBe("123456");
  });

  it("supports digit entry, arrow movement, replacement, and backspace", () => {
    render(<EmailVerificationForm initialUsername="learner01" />);
    const slots = otpDigits();
    (slots[0] as HTMLInputElement).focus();
    fireEvent.change(slots[0]!, { target: { value: "1" } });
    expect(slots[1]).toHaveFocus();
    fireEvent.change(slots[1]!, { target: { value: "2" } });
    expect(slots[2]).toHaveFocus();
    fireEvent.keyDown(slots[2]!, { key: "ArrowLeft" });
    expect(slots[1]).toHaveFocus();
    fireEvent.change(slots[1]!, { target: { value: "9" } });
    expect(slots[1]).toHaveValue("9");
    fireEvent.keyDown(slots[2]!, { key: "Backspace" });
    expect(slots[1]).toHaveFocus();
    expect(slots[1]).toHaveValue("");
    fireEvent.keyDown(slots[1]!, { key: "ArrowRight" });
    expect(slots[2]).toHaveFocus();
  });

  it("inserts pasted digits from a selected slot, without exceeding six", () => {
    render(<EmailVerificationForm initialUsername="learner01" />);
    pasteOtp("1234567");
    const slots = otpDigits();
    expect(slots.map((slot) => (slot as HTMLInputElement).value).join("")).toBe(
      "123456",
    );
    pasteOtp("89", 2);
    expect(slots.map((slot) => (slot as HTMLInputElement).value).join("")).toBe(
      "128956",
    );
  });

  it("fills the next open slot when typing into an empty later position", () => {
    render(<EmailVerificationForm initialUsername="learner01" />);
    const slots = otpDigits();
    fireEvent.change(slots[4]!, { target: { value: "7" } });
    expect(slots[0]).toHaveValue("7");
    expect(slots[1]).toHaveFocus();
    pasteOtp("890", 5);
    expect(slots.map((slot) => (slot as HTMLInputElement).value).join("")).toBe(
      "7890",
    );
    expect(slots[4]).toHaveFocus();
  });

  it("keeps OTP inputs disabled throughout confirmation and blocks overlap", async () => {
    const pending = deferred<{ ok: true; value: undefined }>();
    auth.confirmVerification.mockReturnValueOnce(pending.promise);
    render(<EmailVerificationForm initialUsername="learner01" />);
    pasteOtp("123456");
    fireEvent.click(screen.getByRole("button", { name: "Verify email" }));
    await waitFor(() => expect(otpDigits()[0]).toBeDisabled());
    expect(auth.confirmVerification).toHaveBeenCalledTimes(1);
    pending.resolve({ ok: true, value: undefined });
    await waitFor(() =>
      expect(navigation.replace).toHaveBeenCalledWith("/login"),
    );
  });
});
