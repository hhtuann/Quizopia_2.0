"use client";

import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import type { AuthenticatedRequestExecutor } from "../../lib/api/authenticated-request";
import type { AuthApiResult } from "./api/auth-api-client";
import type { AuthenticatedUser } from "./model/authenticated-user";
import {
  hasAuthenticatedIdentity,
  type SessionState,
} from "./model/session-state";
import { getAvailableWorkspaces, type Workspace } from "./model/workspace";
import {
  createAuthSessionService,
  type AuthSessionService,
} from "./session/auth-session-service";
import type { SessionRuntime } from "./session/session-runtime";

export interface AuthContextValue {
  readonly activeWorkspace: Workspace | null;
  readonly authenticatedRequests: AuthenticatedRequestExecutor | null;
  readonly availableWorkspaces: readonly Workspace[];
  readonly clearLocalSession: () => void;
  readonly confirmVerification: AuthSessionService["confirmVerification"];
  readonly login: AuthSessionService["login"];
  readonly logout: AuthSessionService["logout"];
  readonly register: AuthSessionService["register"];
  readonly requestVerification: AuthSessionService["requestVerification"];
  readonly session: SessionState;
  readonly switchWorkspace: (workspace: Workspace) => boolean;
  readonly user: AuthenticatedUser | null;
}

export interface AuthProviderProps {
  readonly children: ReactNode;
  readonly runtime?: SessionRuntime;
  readonly service?: AuthSessionService;
}

const AuthContext = createContext<AuthContextValue | null>(null);
const noWorkspaces: readonly Workspace[] = Object.freeze([]);

function sessionUser(session: SessionState) {
  return hasAuthenticatedIdentity(session) ? session.user : null;
}

function unavailableResult<T>(): Promise<AuthApiResult<T>> {
  return Promise.resolve({
    ok: false,
    error: {
      kind: "authentication-failure",
      reason: "auth-service-unavailable",
    },
  });
}

export function AuthProvider({
  children,
  runtime,
  service,
}: AuthProviderProps) {
  const [defaultService] = useState(() => createAuthSessionService());
  const activeService =
    service ?? (runtime === undefined ? defaultService : null);
  const activeRuntime = service?.runtime ?? runtime ?? defaultService.runtime;

  useEffect(() => {
    if (activeService !== null) {
      void activeService.bootstrap();
    }
  }, [activeService]);

  const session = useSyncExternalStore(
    activeRuntime.subscribe,
    activeRuntime.getSnapshot,
    activeRuntime.getSnapshot,
  );

  const value = useMemo<AuthContextValue>(() => {
    const user = sessionUser(session);

    return {
      activeWorkspace: hasAuthenticatedIdentity(session)
        ? session.activeWorkspace
        : null,
      authenticatedRequests: activeService?.authenticatedRequests ?? null,
      availableWorkspaces: user
        ? getAvailableWorkspaces(user.roles)
        : noWorkspaces,
      clearLocalSession: activeRuntime.clearLocalSession,
      confirmVerification:
        activeService?.confirmVerification ?? (() => unavailableResult<void>()),
      login:
        activeService?.login ?? (() => unavailableResult<AuthenticatedUser>()),
      logout: activeService?.logout ?? (() => unavailableResult<void>()),
      register:
        activeService?.register ??
        (() => unavailableResult<"VERIFICATION_REQUIRED">()),
      requestVerification:
        activeService?.requestVerification ??
        (() => unavailableResult<"VERIFICATION_REQUEST_ACCEPTED">()),
      session,
      switchWorkspace: activeRuntime.switchWorkspace,
      user,
    };
  }, [activeRuntime, activeService, session]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);

  if (context === null) {
    throw new Error("useAuth must be used within AuthProvider.");
  }

  return context;
}
