import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../../features/auth/auth-provider";
import { AuthenticatedBoundary } from "../../features/auth/components/authenticated-boundary";
import {
  createAuthenticatedUser,
  type AuthRole,
} from "../../features/auth/model/authenticated-user";
import { createAccessTokenVault } from "../../features/auth/session/access-token-vault";
import type { AuthSessionService } from "../../features/auth/session/auth-session-service";
import { createSessionRuntime } from "../../features/auth/session/session-runtime";
import { SkipLink } from "../ui/skip-link";
import { ApplicationHome } from "./application-home";
import { ApplicationShell } from "./application-shell";

const userId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const accessToken = "shell-test-access-token";

afterEach(() => {
  vi.restoreAllMocks();
});

function renderAuthenticatedShell(roles: readonly AuthRole[]) {
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
    login: vi.fn(),
    logout: vi.fn(async () => {
      runtime.clearLocalSession();
      return { ok: true as const, value: undefined };
    }),
    register: vi.fn(),
    requestVerification: vi.fn(),
    runtime,
  };

  render(
    <AuthProvider service={service}>
      <SkipLink href="#main-content">Skip to main content</SkipLink>
      <AuthenticatedBoundary>
        <ApplicationShell>
          <ApplicationHome />
        </ApplicationShell>
      </AuthenticatedBoundary>
    </AuthProvider>,
  );

  return { runtime, service, user, vault };
}

describe("application shell workspace presentation", () => {
  it.each<readonly [string, readonly AuthRole[], string]>([
    ["student", ["STUDENT"], "Learning workspace"],
    ["teacher", ["TEACHER"], "Teaching workspace"],
    ["student and teacher", ["STUDENT", "TEACHER"], "Learning workspace"],
    ["admin", ["ADMIN"], "No workspace"],
    ["admin and student", ["ADMIN", "STUDENT"], "Learning workspace"],
    ["admin and teacher", ["ADMIN", "TEACHER"], "Teaching workspace"],
    ["all roles", ["ADMIN", "STUDENT", "TEACHER"], "Learning workspace"],
  ])(
    "derives workspace context for %s presentation",
    (_name, roles, expectedWorkspace) => {
      renderAuthenticatedShell(roles);

      const context = screen.getByRole("region", {
        name: "Current account context",
      });
      expect(within(context).getByText(expectedWorkspace)).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /Open user menu for learner01/ }),
      ).toHaveTextContent(expectedWorkspace.replace(" workspace", ""));
      expect(
        screen.queryByText("Workspace", { selector: "span" }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("group", { name: "Choose workspace" }),
      ).not.toBeInTheDocument();
    },
  );

  it("switches workspace without changing identity, roles, token, or network state", () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockRejectedValue(new Error("Unexpected network call"));
    const { runtime, user, vault } = renderAuthenticatedShell([
      "STUDENT",
      "TEACHER",
    ]);
    const rolesBeforeSwitch = user.roles;

    fireEvent.click(
      screen.getByRole("button", { name: /Open user menu for learner01/ }),
    );
    fireEvent.click(
      screen.getByRole("menuitem", { name: "Switch to Teaching" }),
    );

    const context = screen.getByRole("region", {
      name: "Current account context",
    });
    expect(within(context).getByText("Teaching workspace")).toBeInTheDocument();
    expect(runtime.getSnapshot()).toMatchObject({
      activeWorkspace: "TEACHING",
      status: "authenticated",
      user: { id: userId, roles: rolesBeforeSwitch },
    });

    fireEvent.click(
      screen.getByRole("button", { name: /Open user menu for learner01/ }),
    );
    fireEvent.click(
      screen.getByRole("menuitem", { name: "Switch to Learning" }),
    );
    expect(within(context).getByText("Learning workspace")).toBeInTheDocument();
    expect(runtime.getSnapshot()).toMatchObject({
      activeWorkspace: "LEARNING",
      status: "authenticated",
      user: { id: userId, roles: rolesBeforeSwitch },
    });
    expect(user.roles).toBe(rolesBeforeSwitch);
    expect(vault.read()).toBe(accessToken);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("shows username, fallback avatar, truthful teacher registration, and closes with Escape", () => {
    renderAuthenticatedShell(["STUDENT"]);

    const trigger = screen.getByRole("button", {
      name: /Open user menu for learner01, Learning workspace/,
    });
    expect(trigger).toHaveTextContent("learner01");
    expect(
      screen.getByRole("img", { name: "learner01 avatar fallback" }),
    ).toHaveTextContent("L");
    fireEvent.click(trigger);
    expect(
      screen.getByRole("menuitem", { name: /Register as teacher/ }),
    ).toHaveAttribute("aria-disabled", "true");
    expect(
      screen.getByRole("menuitem", { name: /Account settings/ }),
    ).toHaveAttribute("aria-disabled", "true");
    const accountSettings = screen.getByRole("menuitem", {
      name: /Account settings/,
    });
    const teacherRegistration = screen.getByRole("menuitem", {
      name: /Register as teacher/,
    });
    expect(accountSettings).toHaveFocus();
    fireEvent.keyDown(accountSettings, { key: "ArrowDown" });
    expect(teacherRegistration).toHaveFocus();
    fireEvent.keyDown(teacherRegistration, { key: "Escape" });
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });
});

describe("application shell session behavior and semantics", () => {
  it("provides stable landmarks, current navigation, and a valid skip target", () => {
    renderAuthenticatedShell(["STUDENT", "TEACHER"]);

    expect(screen.getByRole("banner")).toBeInTheDocument();
    expect(
      screen.getByRole("navigation", { name: "Primary navigation" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveAttribute("id", "main-content");
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
    expect(
      screen.getByRole("link", { name: "Application home" }),
    ).toHaveAttribute("aria-current", "page");
    expect(
      screen.getByRole("button", { name: /Open user menu for learner01/ }),
    ).toBeEnabled();
    expect(
      screen.getByRole("link", { name: "Skip to main content" }),
    ).toHaveAttribute("href", "#main-content");
    expect(document.body).not.toHaveTextContent(accessToken);
  });

  it("keeps shell content visible and announces a refreshing session", () => {
    const { runtime } = renderAuthenticatedShell(["STUDENT"]);

    act(() => {
      runtime.beginRefreshing();
    });

    expect(
      screen.getByRole("heading", { name: "Your Quizopia workspace" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Updating your session",
    );
    expect(
      screen.getByRole("button", { name: /Open user menu for learner01/ }),
    ).toBeEnabled();
    expect(document.body).not.toHaveTextContent(accessToken);
  });

  it("calls logout, clears the local session, and returns to auth-required UX without network", () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockRejectedValue(new Error("Unexpected network call"));
    const { runtime, service, vault } = renderAuthenticatedShell([
      "STUDENT",
      "TEACHER",
    ]);

    fireEvent.click(screen.getByRole("button", { name: /Open user menu/ }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Sign out" }));

    expect(runtime.getSnapshot()).toEqual({ status: "anonymous" });
    expect(vault.read()).toBeNull();
    expect(service.logout).toHaveBeenCalledTimes(1);
    expect(
      screen.getByRole("heading", { name: "Sign in to continue" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("banner")).not.toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("keeps the shell active and announces a server logout failure", async () => {
    const { runtime, service, vault } = renderAuthenticatedShell(["STUDENT"]);
    vi.mocked(service.logout).mockResolvedValueOnce({
      ok: false,
      error: {
        kind: "transport-error",
        error: { kind: "network" },
      },
    });

    fireEvent.click(screen.getByRole("button", { name: /Open user menu/ }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Sign out" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Sign out could not be completed",
    );
    expect(screen.getByRole("status")).toHaveTextContent(
      "Your session is still active",
    );
    expect(runtime.getSnapshot().status).toBe("authenticated");
    expect(vault.read()).toBe(accessToken);
    expect(screen.getByRole("banner")).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Sign out" })).toBeEnabled();
  });
});
