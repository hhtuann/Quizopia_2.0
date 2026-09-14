import type { AuthenticatedUser } from "./authenticated-user";
import type { Workspace } from "./workspace";

export interface BootstrappingSession {
  readonly status: "bootstrapping";
}

export interface AnonymousSession {
  readonly status: "anonymous";
}

export interface AuthenticatedSession {
  readonly status: "authenticated";
  readonly user: AuthenticatedUser;
  readonly activeWorkspace: Workspace | null;
}

export interface RefreshingSession {
  readonly status: "refreshing";
  readonly user: AuthenticatedUser;
  readonly activeWorkspace: Workspace | null;
}

export interface ExpiredSession {
  readonly status: "session-expired";
}

export type SessionState =
  | BootstrappingSession
  | AnonymousSession
  | AuthenticatedSession
  | RefreshingSession
  | ExpiredSession;

export const BOOTSTRAPPING_SESSION: BootstrappingSession = Object.freeze({
  status: "bootstrapping",
});

export const ANONYMOUS_SESSION: AnonymousSession = Object.freeze({
  status: "anonymous",
});

export const EXPIRED_SESSION: ExpiredSession = Object.freeze({
  status: "session-expired",
});

export function hasAuthenticatedIdentity(
  session: SessionState,
): session is AuthenticatedSession | RefreshingSession {
  return session.status === "authenticated" || session.status === "refreshing";
}
