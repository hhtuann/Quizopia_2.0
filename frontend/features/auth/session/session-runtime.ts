import type { AuthenticatedUser } from "../model/authenticated-user";
import {
  ANONYMOUS_SESSION,
  BOOTSTRAPPING_SESSION,
  EXPIRED_SESSION,
  hasAuthenticatedIdentity,
  type AuthenticatedSession,
  type RefreshingSession,
  type SessionState,
} from "../model/session-state";
import {
  deriveDefaultWorkspace,
  getAvailableWorkspaces,
  reconcileWorkspace,
  type Workspace,
} from "../model/workspace";
import type { AccessTokenVault } from "./access-token-vault";

export interface AuthenticatedSessionInput {
  readonly accessToken: string;
  readonly user: AuthenticatedUser;
}

export interface SessionRuntime {
  beginRefreshing(): boolean;
  clearLocalSession(): void;
  completeBootstrapAsAnonymous(): boolean;
  completeRefreshing(input: AuthenticatedSessionInput): boolean;
  establishAuthenticatedSession(input: AuthenticatedSessionInput): boolean;
  expireSession(): boolean;
  getSnapshot(): SessionState;
  recoverFromRefreshFailure(): boolean;
  subscribe(listener: () => void): () => void;
  switchWorkspace(workspace: Workspace): boolean;
}

export function createSessionRuntime(
  accessTokenVault: AccessTokenVault,
): SessionRuntime {
  let state: SessionState = BOOTSTRAPPING_SESSION;
  const listeners = new Set<() => void>();

  function publish(nextState: SessionState) {
    if (nextState === state) {
      return;
    }

    state = nextState;
    listeners.forEach((listener) => listener());
  }

  function activeWorkspaceFor(
    user: AuthenticatedUser,
    previousState: SessionState,
  ): Workspace | null {
    if (
      hasAuthenticatedIdentity(previousState) &&
      previousState.user.userId === user.userId
    ) {
      return reconcileWorkspace(user.roles, previousState.activeWorkspace);
    }

    return deriveDefaultWorkspace(user.roles);
  }

  function authenticatedState(
    user: AuthenticatedUser,
    activeWorkspace: Workspace | null,
  ): AuthenticatedSession {
    return Object.freeze({
      status: "authenticated",
      user,
      activeWorkspace,
    });
  }

  const runtime: SessionRuntime = {
    beginRefreshing() {
      if (state.status !== "authenticated") {
        return false;
      }

      const refreshingState: RefreshingSession = Object.freeze({
        status: "refreshing",
        user: state.user,
        activeWorkspace: state.activeWorkspace,
      });
      publish(refreshingState);
      return true;
    },

    clearLocalSession() {
      accessTokenVault.clear();
      publish(ANONYMOUS_SESSION);
    },

    completeBootstrapAsAnonymous() {
      if (state.status !== "bootstrapping") {
        return false;
      }

      accessTokenVault.clear();
      publish(ANONYMOUS_SESSION);
      return true;
    },

    completeRefreshing(input) {
      if (state.status !== "refreshing") {
        return false;
      }

      const activeWorkspace = activeWorkspaceFor(input.user, state);
      accessTokenVault.replace(input.accessToken);
      publish(authenticatedState(input.user, activeWorkspace));
      return true;
    },

    establishAuthenticatedSession(input) {
      if (
        state.status !== "bootstrapping" &&
        state.status !== "anonymous" &&
        state.status !== "session-expired"
      ) {
        return false;
      }

      accessTokenVault.replace(input.accessToken);
      publish(
        authenticatedState(
          input.user,
          deriveDefaultWorkspace(input.user.roles),
        ),
      );
      return true;
    },

    expireSession() {
      if (!hasAuthenticatedIdentity(state)) {
        return false;
      }

      accessTokenVault.clear();
      publish(EXPIRED_SESSION);
      return true;
    },

    getSnapshot() {
      return state;
    },

    recoverFromRefreshFailure() {
      if (state.status !== "refreshing") {
        return false;
      }

      publish(authenticatedState(state.user, state.activeWorkspace));
      return true;
    },

    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    switchWorkspace(workspace) {
      if (!hasAuthenticatedIdentity(state)) {
        return false;
      }

      if (!getAvailableWorkspaces(state.user.roles).includes(workspace)) {
        return false;
      }

      if (state.activeWorkspace === workspace) {
        return true;
      }

      if (state.status === "authenticated") {
        publish(authenticatedState(state.user, workspace));
      } else {
        const refreshingState: RefreshingSession = Object.freeze({
          status: "refreshing",
          user: state.user,
          activeWorkspace: workspace,
        });
        publish(refreshingState);
      }

      return true;
    },
  };

  return Object.freeze(runtime);
}
