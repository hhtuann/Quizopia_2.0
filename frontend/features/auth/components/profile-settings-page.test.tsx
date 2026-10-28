import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import type {
  HttpRequest,
  HttpTransportResult,
} from "../../../lib/api/http-transport";
import { LanguageSwitcher } from "../../../components/ui/language-switcher";
import { I18nProvider } from "../../../lib/i18n/provider";
import { AuthProvider } from "../auth-provider";
import { createAuthSessionService } from "../session/auth-session-service";
import { ProfileSettingsPage } from "./profile-settings-page";

vi.mock("../../../components/shell/application-shell", () => ({
  ApplicationShell: ({ children }: { children: ReactNode }) => (
    <main>{children}</main>
  ),
}));

const user = {
  id: "first-user",
  username: "learner",
  email: "learner@gmail.com",
  roles: ["STUDENT"] as const,
  displayName: "Original",
};
const success: HttpTransportResult = {
  kind: "response",
  response: {
    ok: true,
    status: 204,
    headers: new Headers(),
    body: { kind: "empty" },
  },
};

function setup(
  execute = vi
    .fn<(request: HttpRequest) => Promise<HttpTransportResult>>()
    .mockResolvedValue(success),
) {
  const service = createAuthSessionService({ transport: { execute } });
  service.runtime.establishAuthenticatedSession({
    accessToken: "memory-only",
    user,
  });
  render(
    <I18nProvider initialLocale="en">
      <AuthProvider service={service}>
        <LanguageSwitcher />
        <ProfileSettingsPage />
      </AuthProvider>
    </I18nProvider>,
  );
  return { service, execute };
}

describe("self-service profile settings", () => {
  it("saves normalized input through the real authenticated executor and clears with null", async () => {
    const { execute, service } = setup();
    expect(screen.getByText(/not a verified legal name/)).toBeVisible();
    const field = screen.getByLabelText("Display name (optional)");
    expect(field).toHaveValue("Original");
    fireEvent.change(field, { target: { value: "  Nguyễn An  " } });
    fireEvent.click(screen.getByRole("button", { name: "Save display name" }));
    expect(await screen.findByText("Display name saved.")).toHaveAttribute(
      "role",
      "status",
    );
    expect(field).toHaveValue("Nguyễn An");
    expect(service.runtime.getSnapshot()).toMatchObject({
      user: { displayName: "Nguyễn An" },
    });
    fireEvent.change(field, { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Save display name" }));
    await waitFor(() => expect(execute).toHaveBeenCalledTimes(2));
    expect(execute.mock.calls.map(([r]) => JSON.parse(String(r.body)))).toEqual(
      [{ displayName: "Nguyễn An" }, { displayName: null }],
    );
    expect(
      execute.mock.calls.every(
        ([r]) =>
          new Headers(r.headers).get("authorization") === "Bearer memory-only",
      ),
    ).toBe(true);
  });

  it("preserves draft and validation across locale changes without network calls", async () => {
    const { execute } = setup();
    fireEvent.change(screen.getByLabelText("Display name (optional)"), {
      target: { value: "a".repeat(101) },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save display name" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "1–100 characters",
    );
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "vi" } });
    expect(screen.getByLabelText("Tên hiển thị (không bắt buộc)")).toHaveValue(
      "a".repeat(101),
    );
    expect(screen.getByRole("alert")).toHaveTextContent("1–100 ký tự");
    expect(
      screen.getByText(/không phải tên pháp lý đã xác minh/),
    ).toBeVisible();
    expect(execute).not.toHaveBeenCalled();
  });

  it("keeps a failed edit for explicit retry without changing the in-memory name", async () => {
    const execute = vi
      .fn<(request: HttpRequest) => Promise<HttpTransportResult>>()
      .mockResolvedValueOnce({
        kind: "transport-error",
        error: { kind: "network" },
      })
      .mockResolvedValue(success);
    const { service } = setup(execute);
    fireEvent.change(screen.getByLabelText("Display name (optional)"), {
      target: { value: "New name" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save display name" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not be saved",
    );
    expect(screen.getByLabelText("Display name (optional)")).toHaveValue(
      "New name",
    );
    expect(service.runtime.getSnapshot()).toMatchObject({
      user: { displayName: "Original" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save display name" }));
    expect(await screen.findByText("Display name saved.")).toHaveAttribute(
      "role",
      "status",
    );
    expect(execute).toHaveBeenCalledTimes(2);
  });

  it.each([false, true])(
    "discards drafts and late feedback when session is replaced (same account: %j)",
    async (sameAccount) => {
      let resolve!: (response: HttpTransportResult) => void;
      const execute = vi.fn<
        (request: HttpRequest) => Promise<HttpTransportResult>
      >(
        () =>
          new Promise((done) => {
            resolve = done;
          }),
      );
      const { service } = setup(execute);
      fireEvent.change(screen.getByLabelText("Display name (optional)"), {
        target: { value: "Private draft" },
      });
      const button = screen.getByRole("button", { name: "Save display name" });
      fireEvent.click(button);
      await waitFor(() => expect(button).toBeDisabled());
      fireEvent.click(button);
      expect(execute).toHaveBeenCalledTimes(1);
      act(() => {
        service.runtime.clearLocalSession();
        service.runtime.establishAuthenticatedSession({
          accessToken: "replacement",
          user: {
            ...user,
            id: sameAccount ? user.id : "second-user",
            displayName: "Replacement name",
          },
        });
      });
      expect(screen.getByLabelText("Display name (optional)")).toHaveValue(
        "Replacement name",
      );
      await act(async () => resolve(success));
      expect(screen.queryByText("Display name saved.")).not.toBeInTheDocument();
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      expect(screen.getByLabelText("Display name (optional)")).toHaveValue(
        "Replacement name",
      );
    },
  );

  it("removes profile content immediately on logout", () => {
    const { service } = setup();
    act(() => service.runtime.clearLocalSession());
    expect(
      screen.queryByLabelText("Display name (optional)"),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Sign in to continue" }),
    ).toBeVisible();
  });
});
