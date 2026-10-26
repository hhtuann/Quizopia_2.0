import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../../features/auth/auth-provider";
import {
  createAuthenticatedUser,
  type AuthRole,
} from "../../features/auth/model/authenticated-user";
import { createAccessTokenVault } from "../../features/auth/session/access-token-vault";
import type { AuthSessionService } from "../../features/auth/session/auth-session-service";
import { createSessionRuntime } from "../../features/auth/session/session-runtime";
import { I18nProvider } from "../../lib/i18n/provider";
import type { Locale } from "../../lib/i18n/core";
import { AuthenticatedUserMenu } from "./authenticated-user-menu";

const navigation = vi.hoisted(() => ({ replace: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => navigation,
}));

const userId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const accessToken = "user-menu-test-access-token";

afterEach(() => {
  vi.restoreAllMocks();
  navigation.replace.mockClear();
});

function renderAuthenticatedUserMenu(
  roles: readonly AuthRole[],
  initialLocale: Locale = "en",
) {
  const vault = createAccessTokenVault();
  const runtime = createSessionRuntime(vault);
  const user = createAuthenticatedUser({
    email: "learner01@gmail.com",
    id: userId,
    roles,
    username: "learner01",
  });
  runtime.establishAuthenticatedSession({ accessToken, user });
  const service: AuthSessionService = {
    authenticatedRequests: {
      execute: vi.fn(),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    },
    bootstrap: vi.fn(async () => ({ status: "no-session" as const })),
    confirmVerification: vi.fn(),
    enableTeacher: vi.fn(async () => ({
      ok: false as const,
      error: {
        kind: "transport-error" as const,
        error: { kind: "network" as const },
      },
    })),
    login: vi.fn(),
    logout: vi.fn(async () => {
      runtime.clearLocalSession();
      return { ok: true as const, value: undefined };
    }),
    register: vi.fn(),
    requestVerification: vi.fn(),
    retryVerification: vi.fn(),
    readAccessToken: () => null,
    runtime,
  };

  render(
    <AuthProvider service={service}>
      <I18nProvider initialLocale={initialLocale}>
        <AuthenticatedUserMenu />
      </I18nProvider>
    </AuthProvider>,
  );

  return { runtime, service, user, vault };
}

function openMenu() {
  fireEvent.click(
    screen.getByRole("button", { name: /Open user menu for learner01/ }),
  );
}

describe("authenticated user menu language section", () => {
  it("exposes a bilingual language group with the current locale indicated", () => {
    renderAuthenticatedUserMenu(["STUDENT"]);
    openMenu();

    const language = screen.getByRole("group", {
      name: "Language / Ngôn ngữ",
    });
    const vietnamese = within(language).getByRole("menuitemradio", {
      name: "Tiếng Việt",
    });
    const english = within(language).getByRole("menuitemradio", {
      name: "English",
    });
    expect(vietnamese).toHaveAttribute("aria-checked", "false");
    expect(english).toHaveAttribute("aria-checked", "true");
    // The visual check icon marks the current option redundantly for sighted
    // users; aria-checked carries the state for assistive technology.
    expect(english.querySelector("svg")).not.toBeNull();
    expect(vietnamese.querySelector("svg")).toBeNull();
  });

  it("switches locale immediately, persists the cookie, and keeps the menu open", () => {
    document.cookie = "quizopia-locale=; Path=/; Max-Age=0";
    renderAuthenticatedUserMenu(["STUDENT"]);
    openMenu();

    fireEvent.click(screen.getByRole("menuitemradio", { name: "Tiếng Việt" }));

    expect(document.documentElement.lang).toBe("vi");
    expect(document.cookie).toContain("quizopia-locale=vi");
    // The menu stays open and immediately renders in the new locale.
    expect(screen.getByRole("menu")).toBeInTheDocument();
    expect(
      screen.getByRole("menuitem", { name: "Đăng xuất" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("menuitemradio", { name: "Tiếng Việt" }),
    ).toHaveAttribute("aria-checked", "true");
    expect(
      screen.getByRole("menuitemradio", { name: "English" }),
    ).toHaveAttribute("aria-checked", "false");

    // Switching back is immediate and reversible.
    fireEvent.click(screen.getByRole("menuitemradio", { name: "English" }));
    expect(document.documentElement.lang).toBe("en");
    expect(document.cookie).toContain("quizopia-locale=en");
    expect(
      screen.getByRole("menuitem", { name: "Sign out" }),
    ).toBeInTheDocument();
  });

  it("supports the vi default locale and marks Tiếng Việt as current", () => {
    renderAuthenticatedUserMenu(["STUDENT"], "vi");
    fireEvent.click(
      screen.getByRole("button", {
        name: /Mở menu người dùng cho learner01/,
      }),
    );

    expect(
      screen.getByRole("menuitemradio", { name: "Tiếng Việt" }),
    ).toHaveAttribute("aria-checked", "true");
    expect(
      screen.getByRole("menuitemradio", { name: "English" }),
    ).toHaveAttribute("aria-checked", "false");
  });

  it("reaches the language options with arrow keys; Escape closes and restores focus", () => {
    renderAuthenticatedUserMenu(["STUDENT"]);
    const trigger = screen.getByRole("button", {
      name: /Open user menu for learner01/,
    });
    fireEvent.click(trigger);

    const accountSettings = screen.getByRole("menuitem", {
      name: /Account settings/,
    });
    expect(accountSettings).toHaveFocus();
    fireEvent.keyDown(accountSettings, { key: "ArrowDown" });
    expect(
      screen.getByRole("menuitem", { name: /Register as teacher/ }),
    ).toHaveFocus();
    fireEvent.keyDown(document.activeElement as HTMLElement, {
      key: "ArrowDown",
    });
    expect(
      screen.getByRole("menuitemradio", { name: "Tiếng Việt" }),
    ).toHaveFocus();
    fireEvent.keyDown(document.activeElement as HTMLElement, {
      key: "ArrowDown",
    });
    expect(
      screen.getByRole("menuitemradio", { name: "English" }),
    ).toHaveFocus();
    fireEvent.keyDown(document.activeElement as HTMLElement, {
      key: "ArrowDown",
    });
    expect(screen.getByRole("menuitem", { name: "Sign out" })).toHaveFocus();

    fireEvent.keyDown(document.activeElement as HTMLElement, {
      key: "Escape",
    });
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("still signs out correctly after switching locale inside the menu", async () => {
    const { service, vault } = renderAuthenticatedUserMenu(["STUDENT"]);
    openMenu();
    fireEvent.click(screen.getByRole("menuitemradio", { name: "Tiếng Việt" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Đăng xuất" }));

    expect(service.logout).toHaveBeenCalledTimes(1);
    await waitFor(() =>
      expect(navigation.replace).toHaveBeenCalledWith("/login"),
    );
    expect(vault.read()).toBeNull();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });
});
