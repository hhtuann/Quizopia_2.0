export const AUTH_ROLES = ["STUDENT", "TEACHER", "ADMIN"] as const;

export type AuthRole = (typeof AUTH_ROLES)[number];

export interface AuthenticatedUser {
  readonly email: string;
  readonly id: string;
  readonly roles: readonly AuthRole[];
  readonly username: string;
}

const acceptedRoles = new Set<string>(AUTH_ROLES);

export function isAuthRole(value: unknown): value is AuthRole {
  return typeof value === "string" && acceptedRoles.has(value);
}

export function createAuthenticatedUser(input: {
  readonly email: string;
  readonly id: string;
  readonly roles: readonly unknown[];
  readonly username: string;
}): AuthenticatedUser {
  if (input.id.length === 0) {
    throw new TypeError("Authenticated user ID must not be empty.");
  }
  if (input.username.length === 0) {
    throw new TypeError("Authenticated username must not be empty.");
  }
  if (input.email.length === 0) {
    throw new TypeError("Authenticated email must not be empty.");
  }

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
    email: input.email,
    id: input.id,
    roles: Object.freeze(roles),
    username: input.username,
  });
}

export function hasRole(user: AuthenticatedUser, role: AuthRole): boolean {
  return user.roles.includes(role);
}
