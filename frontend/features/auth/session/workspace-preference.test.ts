import { afterEach, describe, expect, it } from "vitest";
import { createAccessTokenVault } from "./access-token-vault";
import { createSessionRuntime } from "./session-runtime";
import type { AuthenticatedUser } from "../model/authenticated-user";

const dualRoleUser: AuthenticatedUser = {
  id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  username: "learner01",
  email: "learner01@gmail.com",
  roles: ["STUDENT", "TEACHER"],
};
const studentOnlyUser: AuthenticatedUser = {
  id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  username: "learner01",
  email: "learner01@gmail.com",
  roles: ["STUDENT"],
};
const otherUser: AuthenticatedUser = {
  id: "a7a4c564-3c27-4e6d-91aa-a004334aa8f9",
  username: "teacher01",
  email: "teacher01@gmail.com",
  roles: ["STUDENT", "TEACHER"],
};

function runtime() {
  return createSessionRuntime(createAccessTokenVault());
}

function activeWorkspaceOf(
  state: ReturnType<ReturnType<typeof runtime>["getSnapshot"]>,
): string {
  return (state as { activeWorkspace?: string }).activeWorkspace ?? "none";
}

/** Real reload shape: a fresh runtime bootstraps the same identity. */
function bootstrap(r = runtime(), user: AuthenticatedUser = dualRoleUser) {
  r.establishAuthenticatedSession({ accessToken: "token", user }, undefined);
  return r;
}

afterEach(() => {
  globalThis.localStorage?.clear();
});

describe("workspace preference persistence", () => {
  it("switching workspace persists across a reload-shaped bootstrap", () => {
    const first = bootstrap();
    expect(activeWorkspaceOf(first.getSnapshot())).toBe("LEARNING");
    expect(first.switchWorkspace("TEACHING")).toBe(true);
    expect(activeWorkspaceOf(first.getSnapshot())).toBe("TEACHING");

    // Reload: a brand-new runtime bootstraps the same identity.
    const reloaded = bootstrap();
    expect(activeWorkspaceOf(reloaded.getSnapshot())).toBe("TEACHING");
  });

  it("a stored persona no longer permitted by current roles falls back to the role default", () => {
    const first = bootstrap();
    first.switchWorkspace("TEACHING");

    // Same identity returns with the TEACHER role revoked.
    const reloaded = bootstrap(undefined, studentOnlyUser);
    expect(activeWorkspaceOf(reloaded.getSnapshot())).toBe("LEARNING");
  });

  it("the preference is per-account and never leaks across identities", () => {
    const first = bootstrap();
    first.switchWorkspace("TEACHING");

    const other = bootstrap(undefined, otherUser);
    expect(activeWorkspaceOf(other.getSnapshot())).toBe("LEARNING");
  });

  it("corrupted storage values are ignored safely", () => {
    globalThis.localStorage?.setItem(
      "quizopia-workspace:" + dualRoleUser.id,
      "GARBAGE",
    );
    const r = bootstrap();
    expect(activeWorkspaceOf(r.getSnapshot())).toBe("LEARNING");
  });

  it("switching to the already-active workspace reports success and keeps the persona stable", () => {
    const r = bootstrap();
    expect(r.switchWorkspace("LEARNING")).toBe(true);
    expect(activeWorkspaceOf(r.getSnapshot())).toBe("LEARNING");
    expect(activeWorkspaceOf(bootstrap().getSnapshot())).toBe("LEARNING");
  });

  it("an in-session refresh for the same identity keeps the live persona even when storage is stale", () => {
    const first = bootstrap();
    first.switchWorkspace("TEACHING");
    // Storage is externally reverted (e.g. another tab); the live session
    // persona must survive its own token refresh.
    globalThis.localStorage?.setItem(
      "quizopia-workspace:" + dualRoleUser.id,
      "LEARNING",
    );
    first.beginRefreshing();
    first.completeRefreshing({
      accessToken: "token2",
      user: dualRoleUser,
    });
    expect(activeWorkspaceOf(first.getSnapshot())).toBe("TEACHING");
  });
});
