export const AUTH_ROLES = ["STUDENT", "TEACHER", "ADMIN"] as const;

export type AuthRole = (typeof AUTH_ROLES)[number];

export interface AuthenticatedUser {
  readonly userId: string;
  readonly roles: readonly AuthRole[];
}

const acceptedRoles = new Set<string>(AUTH_ROLES);

export function isAuthRole(value: unknown): value is AuthRole {
  return typeof value === "string" && acceptedRoles.has(value);
}

export function createAuthenticatedUser(input: {
  readonly userId: string;
  readonly roles: readonly unknown[];
}): AuthenticatedUser {
  const roles: AuthRole[] = [];

  for (const role of input.roles) {
    if (!isAuthRole(role)) {
      throw new TypeError("Authenticated user contains an unknown role.");
    }

    if (!roles.includes(role)) {
      roles.push(role);
    }
  }

  return Object.freeze({
    userId: input.userId,
    roles: Object.freeze(roles),
  });
}

export function hasRole(user: AuthenticatedUser, role: AuthRole): boolean {
  return user.roles.includes(role);
}
