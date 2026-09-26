import { describe, expect, it } from "vitest";
import type { AuthRole } from "./authenticated-user";
import {
  deriveDefaultWorkspace,
  getAvailableWorkspaces,
  reconcileWorkspace,
  type Workspace,
} from "./workspace";

describe("workspace derivation", () => {
  it.each<
    readonly [
      string,
      readonly AuthRole[],
      readonly Workspace[],
      Workspace | null,
    ]
  >([
    ["no roles", [], [], null],
    ["student", ["STUDENT"], ["LEARNING"], "LEARNING"],
    ["teacher", ["TEACHER"], ["TEACHING"], "TEACHING"],
    [
      "student and teacher",
      ["STUDENT", "TEACHER"],
      ["LEARNING", "TEACHING"],
      "LEARNING",
    ],
    ["admin", ["ADMIN"], [], null],
    ["admin and student", ["ADMIN", "STUDENT"], ["LEARNING"], "LEARNING"],
    ["admin and teacher", ["ADMIN", "TEACHER"], ["TEACHING"], "TEACHING"],
    [
      "all roles",
      ["ADMIN", "STUDENT", "TEACHER"],
      ["LEARNING", "TEACHING"],
      "LEARNING",
    ],
  ])(
    "derives the available and default workspace for %s",
    (_name, roles, expectedAvailable, expectedDefault) => {
      expect(getAvailableWorkspaces(roles)).toEqual(expectedAvailable);
      expect(deriveDefaultWorkspace(roles)).toBe(expectedDefault);
    },
  );

  it("keeps a current workspace only while it remains eligible", () => {
    expect(reconcileWorkspace(["STUDENT", "TEACHER"], "TEACHING")).toBe(
      "TEACHING",
    );
    expect(reconcileWorkspace(["STUDENT"], "TEACHING")).toBe("LEARNING");
    expect(reconcileWorkspace(["ADMIN"], "LEARNING")).toBeNull();
  });
});
