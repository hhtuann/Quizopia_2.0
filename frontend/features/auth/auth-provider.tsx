"use client";

import {
  createContext,
  useContext,
  useMemo,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import type { AuthenticatedUser } from "./model/authenticated-user";
import {
  hasAuthenticatedIdentity,
  type SessionState,
} from "./model/session-state";
import { getAvailableWorkspaces, type Workspace } from "./model/workspace";
import { createAccessTokenVault } from "./session/access-token-vault";
import {
  createSessionRuntime,
  type SessionRuntime,
} from "./session/session-runtime";

export interface AuthContextValue {
  readonly activeWorkspace: Workspace | null;
  readonly availableWorkspaces: readonly Workspace[];
  readonly clearLocalSession: () => void;
  readonly session: SessionState;
  readonly switchWorkspace: (workspace: Workspace) => boolean;
  readonly user: AuthenticatedUser | null;
}

export interface AuthProviderProps {
  readonly children: ReactNode;
  readonly runtime?: SessionRuntime;
}

const AuthContext = createContext<AuthContextValue | null>(null);
const noWorkspaces: readonly Workspace[] = Object.freeze([]);

function sessionUser(session: SessionState) {
  return hasAuthenticatedIdentity(session) ? session.user : null;
}

export function AuthProvider({ children, runtime }: AuthProviderProps) {
  const [defaultRuntime] = useState(() =>
    createSessionRuntime(createAccessTokenVault()),
  );
  const activeRuntime = runtime ?? defaultRuntime;
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
      availableWorkspaces: user
        ? getAvailableWorkspaces(user.roles)
        : noWorkspaces,
      clearLocalSession: activeRuntime.clearLocalSession,
      session,
      switchWorkspace: activeRuntime.switchWorkspace,
      user,
    };
  }, [activeRuntime, session]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);

  if (context === null) {
    throw new Error("useAuth must be used within AuthProvider.");
  }

  return context;
}
