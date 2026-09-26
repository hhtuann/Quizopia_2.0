import { describe, expect, it, vi } from "vitest";
import {
  createAuthenticatedUser,
  type AuthRole,
} from "../model/authenticated-user";
import { createAccessTokenVault } from "./access-token-vault";
import { createSessionRuntime } from "./session-runtime";

const firstUserId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const secondUserId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";

function sessionInput(
  accessToken: string,
  roles: readonly AuthRole[] = ["STUDENT"],
  userId = firstUserId,
) {
  return {
    accessToken,
    user: createAuthenticatedUser({ userId, roles }),
  };
}

describe("session runtime", () => {
  it("starts bootstrapping and can complete as anonymous", () => {
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);

    expect(runtime.getSnapshot()).toEqual({ status: "bootstrapping" });
    expect(runtime.completeBootstrapAsAnonymous()).toBe(true);
    expect(runtime.getSnapshot()).toEqual({ status: "anonymous" });
    expect(vault.read()).toBeNull();
  });

  it.each([
    ["bootstrapping", false],
    ["anonymous", true],
  ] as const)(
    "establishes an authenticated session from %s using trusted domain input",
    (_startingState, completeAsAnonymous) => {
      const vault = createAccessTokenVault();
      const runtime = createSessionRuntime(vault);
      if (completeAsAnonymous) {
        runtime.completeBootstrapAsAnonymous();
      }
      const input = sessionInput("initial-access-token", [
        "STUDENT",
        "TEACHER",
      ]);

      expect(runtime.establishAuthenticatedSession(input)).toBe(true);
      expect(runtime.getSnapshot()).toEqual({
        status: "authenticated",
        user: input.user,
        activeWorkspace: "LEARNING",
      });
      expect(vault.read()).toBe("initial-access-token");
    },
  );

  it("refreshes while preserving identity, workspace, and the current token", () => {
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);
    const initial = sessionInput("initial-access-token", [
      "STUDENT",
      "TEACHER",
    ]);
    runtime.establishAuthenticatedSession(initial);
    runtime.switchWorkspace("TEACHING");

    expect(runtime.beginRefreshing()).toBe(true);
    expect(runtime.getSnapshot()).toEqual({
      status: "refreshing",
      user: initial.user,
      activeWorkspace: "TEACHING",
    });
    expect(vault.read()).toBe("initial-access-token");

    const refreshed = sessionInput("replacement-access-token", [
      "STUDENT",
      "TEACHER",
    ]);
    expect(runtime.completeRefreshing(refreshed)).toBe(true);
    expect(runtime.getSnapshot()).toEqual({
      status: "authenticated",
      user: refreshed.user,
      activeWorkspace: "TEACHING",
    });
    expect(vault.read()).toBe("replacement-access-token");
  });

  it("re-derives an invalid workspace when refreshed roles change", () => {
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);
    runtime.establishAuthenticatedSession(
      sessionInput("initial-access-token", ["TEACHER"]),
    );
    runtime.beginRefreshing();

    runtime.completeRefreshing(
      sessionInput("replacement-access-token", ["STUDENT"]),
    );

    expect(runtime.getSnapshot()).toMatchObject({
      status: "authenticated",
      activeWorkspace: "LEARNING",
    });
  });

  it("recovers from a transient refresh failure without clearing session data", () => {
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);
    const input = sessionInput("initial-access-token", ["STUDENT", "TEACHER"]);
    runtime.establishAuthenticatedSession(input);
    runtime.switchWorkspace("TEACHING");
    runtime.beginRefreshing();

    expect(runtime.recoverFromRefreshFailure()).toBe(true);
    expect(runtime.getSnapshot()).toEqual({
      status: "authenticated",
      user: input.user,
      activeWorkspace: "TEACHING",
    });
    expect(vault.read()).toBe("initial-access-token");
  });

  it("uses a new identity's default workspace instead of carrying persona state", () => {
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);
    runtime.establishAuthenticatedSession(
      sessionInput("initial-access-token", ["STUDENT", "TEACHER"]),
    );
    runtime.switchWorkspace("TEACHING");
    runtime.beginRefreshing();

    runtime.completeRefreshing(
      sessionInput(
        "replacement-access-token",
        ["STUDENT", "TEACHER"],
        secondUserId,
      ),
    );

    expect(runtime.getSnapshot()).toMatchObject({
      status: "authenticated",
      activeWorkspace: "LEARNING",
      user: { userId: secondUserId },
    });
  });

  it.each([
    ["authenticated", false],
    ["refreshing", true],
  ] as const)(
    "expires an %s session and clears its token",
    (_sessionStatus, beginRefresh) => {
      const vault = createAccessTokenVault();
      const runtime = createSessionRuntime(vault);
      runtime.establishAuthenticatedSession(
        sessionInput("initial-access-token"),
      );
      if (beginRefresh) {
        runtime.beginRefreshing();
      }

      expect(runtime.expireSession()).toBe(true);
      expect(runtime.getSnapshot()).toEqual({ status: "session-expired" });
      expect(vault.read()).toBeNull();
    },
  );

  it("clears authenticated identity, workspace, and token locally", () => {
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);
    runtime.establishAuthenticatedSession(
      sessionInput("initial-access-token", ["STUDENT", "TEACHER"]),
    );
    runtime.switchWorkspace("TEACHING");

    runtime.clearLocalSession();

    expect(runtime.getSnapshot()).toEqual({ status: "anonymous" });
    expect(vault.read()).toBeNull();
  });

  it("switches only to eligible workspaces without changing identity or token", () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);
    const input = sessionInput("initial-access-token", ["STUDENT", "TEACHER"]);
    runtime.establishAuthenticatedSession(input);

    expect(runtime.switchWorkspace("TEACHING")).toBe(true);
    const switched = runtime.getSnapshot();
    expect(switched).toMatchObject({
      status: "authenticated",
      user: input.user,
      activeWorkspace: "TEACHING",
    });
    expect(input.user.roles).toEqual(["STUDENT", "TEACHER"]);
    expect(vault.read()).toBe("initial-access-token");
    expect(fetchSpy).not.toHaveBeenCalled();

    const teacherVault = createAccessTokenVault();
    const teacherOnlyRuntime = createSessionRuntime(teacherVault);
    teacherOnlyRuntime.establishAuthenticatedSession(
      sessionInput("teacher-access-token", ["TEACHER"]),
    );
    const teacherState = teacherOnlyRuntime.getSnapshot();
    expect(teacherOnlyRuntime.switchWorkspace("LEARNING")).toBe(false);
    expect(teacherOnlyRuntime.getSnapshot()).toBe(teacherState);
    expect(teacherVault.read()).toBe("teacher-access-token");
    expect(fetchSpy).not.toHaveBeenCalled();

    fetchSpy.mockRestore();
  });

  it("rejects invalid transitions without replacing the current state", () => {
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);
    const initialState = runtime.getSnapshot();

    expect(
      runtime.completeRefreshing(sessionInput("replacement-access-token")),
    ).toBe(false);
    expect(runtime.beginRefreshing()).toBe(false);
    expect(runtime.expireSession()).toBe(false);
    expect(runtime.getSnapshot()).toBe(initialState);
    expect(vault.read()).toBeNull();
  });
});
