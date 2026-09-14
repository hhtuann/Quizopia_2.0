import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../../features/auth/auth-provider";
import { AuthenticatedBoundary } from "../../features/auth/components/authenticated-boundary";
import {
  createAuthenticatedUser,
  type AuthRole,
} from "../../features/auth/model/authenticated-user";
import { createAccessTokenVault } from "../../features/auth/session/access-token-vault";
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
  const user = createAuthenticatedUser({ userId, roles });
  runtime.establishAuthenticatedSession({ accessToken, user });

  render(
    <AuthProvider runtime={runtime}>
      <SkipLink href="#main-content">Skip to main content</SkipLink>
      <AuthenticatedBoundary>
        <ApplicationShell>
          <ApplicationHome />
        </ApplicationShell>
      </AuthenticatedBoundary>
    </AuthProvider>,
  );

  return { runtime, user, vault };
}

describe("application shell workspace presentation", () => {
  it.each<readonly [string, readonly AuthRole[], string, boolean]>([
    ["student", ["STUDENT"], "Learning workspace", false],
    ["teacher", ["TEACHER"], "Teaching workspace", false],
    ["student and teacher", ["STUDENT", "TEACHER"], "Learning workspace", true],
    ["admin", ["ADMIN"], "No workspace", false],
    ["admin and student", ["ADMIN", "STUDENT"], "Learning workspace", false],
    ["admin and teacher", ["ADMIN", "TEACHER"], "Teaching workspace", false],
    ["all roles", ["ADMIN", "STUDENT", "TEACHER"], "Learning workspace", true],
  ])(
    "derives workspace controls for %s presentation",
    (_name, roles, expectedWorkspace, hasSwitcher) => {
      renderAuthenticatedShell(roles);

      const context = screen.getByRole("region", {
        name: "Current account context",
      });
      expect(within(context).getByText(expectedWorkspace)).toBeInTheDocument();
      if (hasSwitcher) {
        expect(
          screen.getByRole("group", { name: "Choose workspace" }),
        ).toBeInTheDocument();
      } else {
        expect(
          screen.queryByRole("group", { name: "Choose workspace" }),
        ).not.toBeInTheDocument();
      }
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

    const teachingButton = screen.getByRole("button", { name: "Teaching" });
    expect(teachingButton).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(teachingButton);

    expect(teachingButton).toHaveAttribute("aria-pressed", "true");
    const context = screen.getByRole("region", {
      name: "Current account context",
    });
    expect(within(context).getByText("Teaching workspace")).toBeInTheDocument();
    expect(runtime.getSnapshot()).toMatchObject({
      activeWorkspace: "TEACHING",
      status: "authenticated",
      user: { userId, roles: rolesBeforeSwitch },
    });
    expect(user.roles).toBe(rolesBeforeSwitch);
    expect(vault.read()).toBe(accessToken);
    expect(fetchSpy).not.toHaveBeenCalled();
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
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
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
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
    expect(document.body).not.toHaveTextContent(accessToken);
  });

  it("clears the local session and returns to auth-required UX without network", () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockRejectedValue(new Error("Unexpected network call"));
    const { runtime, vault } = renderAuthenticatedShell(["STUDENT", "TEACHER"]);

    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    expect(runtime.getSnapshot()).toEqual({ status: "anonymous" });
    expect(vault.read()).toBeNull();
    expect(
      screen.getByRole("heading", { name: "Sign in to continue" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("banner")).not.toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
