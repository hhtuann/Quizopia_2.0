import { describe, expect, it } from "vitest";
import {
  AUTH_ROLES,
  createAuthenticatedUser,
  hasRole,
  isAuthRole,
  type AuthRole,
} from "./authenticated-user";

const userId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";

describe("authenticated user", () => {
  it.each<readonly [string, readonly AuthRole[]]>([
    ["empty", []],
    ["student", ["STUDENT"]],
    ["teacher", ["TEACHER"]],
    ["admin", ["ADMIN"]],
    ["student and teacher", ["STUDENT", "TEACHER"]],
    ["admin and student", ["ADMIN", "STUDENT"]],
    ["admin and teacher", ["ADMIN", "TEACHER"]],
    ["all accepted roles", ["STUDENT", "TEACHER", "ADMIN"]],
  ])("supports the %s role set", (_name, roles) => {
    const user = createAuthenticatedUser({ userId, roles });

    expect(user.roles).toEqual(roles);
    for (const role of roles) {
      expect(hasRole(user, role)).toBe(true);
    }
  });

  it("recognizes only the closed accepted role vocabulary", () => {
    expect(AUTH_ROLES).toEqual(["STUDENT", "TEACHER", "ADMIN"]);
    expect(isAuthRole("STUDENT")).toBe(true);
    expect(isAuthRole("TEACHER")).toBe(true);
    expect(isAuthRole("ADMIN")).toBe(true);
    expect(isAuthRole("OWNER")).toBe(false);
    expect(isAuthRole("student")).toBe(false);
  });

  it("rejects an unknown role instead of normalizing it", () => {
    expect(() =>
      createAuthenticatedUser({ userId, roles: ["STUDENT", "OWNER"] }),
    ).toThrow(TypeError);
  });
});
