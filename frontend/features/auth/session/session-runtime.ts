import type { AuthenticatedUser } from "../model/authenticated-user";
import {
  ANONYMOUS_SESSION,
  BOOTSTRAP_FAILED_SESSION,
  BOOTSTRAPPING_SESSION,
  EXPIRED_SESSION,
  VERIFICATION_UNAVAILABLE_SESSION,
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
import { restoreWorkspace, storeWorkspace } from "./workspace-preference";
import type { AccessTokenVault } from "./access-token-vault";

export interface AuthenticatedSessionInput {
  readonly accessToken: string;
  readonly user: AuthenticatedUser;
}

export interface SessionRuntime {
  beginOperation(): number;
  getOperationGeneration(): number;
  getSessionGeneration(): number;
  isCurrentOperation(generation: number): boolean;
  prepareLoginHydration(generation: number): boolean;
  subscribeOperations(listener: () => void): () => void;
  beginRefreshing(): boolean;
  clearLocalSession(): void;
  completeBootstrapAsAnonymous(): boolean;
  completeBootstrapAsUnavailable(): boolean;
  completeRefreshing(input: AuthenticatedSessionInput): boolean;
  establishAuthenticatedSession(
    input: AuthenticatedSessionInput,
    operationGeneration?: number,
  ): boolean;
  expireSession(): boolean;
  getSnapshot(): SessionState;
  recoverFromRefreshFailure(): boolean;
  subscribe(listener: () => void): () => void;
  switchWorkspace(workspace: Workspace): boolean;
  markVerificationUnavailable(operationGeneration?: number): void;
  setVerificationPending(
    isVerifying: boolean,
    operationGeneration: number,
  ): void;
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
      previousState.user.id === user.id
    ) {
      return reconcileWorkspace(user.roles, previousState.activeWorkspace);
    }

    // A reload (or any fresh bootstrap for a returning identity) restores the
    // persisted persona, validated against the CURRENT authoritative roles;
    // an absent/stale/no-longer-permitted preference falls back to the role
    // default. Presentation only — never an authorization fact.
    return restoreWorkspace(user.id, user.roles);
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
    beginOperation() {
      const generation = accessTokenVault.invalidateOperations();
      // A superseded refresh must not leave the existing identity refreshing.
      runtime.recoverFromRefreshFailure();
      return generation;
    },
    getOperationGeneration: accessTokenVault.getOperationGeneration,
    getSessionGeneration() {
      return accessTokenVault.readSnapshot().sessionGeneration;
    },
    isCurrentOperation(generation) {
      return generation === accessTokenVault.getOperationGeneration();
    },
    subscribeOperations: accessTokenVault.subscribeOperations,
    prepareLoginHydration(generation) {
      if (!runtime.isCurrentOperation(generation)) return false;
      accessTokenVault.clear();
      publish(ANONYMOUS_SESSION);
      return true;
    },
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
      accessTokenVault.invalidateOperations();
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

    completeBootstrapAsUnavailable() {
      if (state.status !== "bootstrapping") {
        return false;
      }

      accessTokenVault.clear();
      publish(BOOTSTRAP_FAILED_SESSION);
      return true;
    },

    completeRefreshing(input) {
      if (state.status !== "refreshing") {
        return false;
      }

      const identityChanged = state.user.id !== input.user.id;
      const activeWorkspace = activeWorkspaceFor(input.user, state);
      if (identityChanged) {
        accessTokenVault.invalidateOperations();
        accessTokenVault.startSession(input.accessToken);
      } else {
        accessTokenVault.replace(input.accessToken);
      }
      publish(authenticatedState(input.user, activeWorkspace));
      return true;
    },

    establishAuthenticatedSession(input, operationGeneration) {
      if (
        operationGeneration !== undefined &&
        !runtime.isCurrentOperation(operationGeneration)
      )
        return false;
      if (
        state.status !== "bootstrapping" &&
        state.status !== "anonymous" &&
        state.status !== "bootstrap-failed" &&
        state.status !== "verification-unavailable" &&
        state.status !== "session-expired"
      ) {
        return false;
      }

      if (operationGeneration === undefined)
        accessTokenVault.invalidateOperations();
      accessTokenVault.startSession(input.accessToken);
      // Fresh login restores the account's persisted persona (validated
      // against current roles), matching reload behavior.
      publish(
        authenticatedState(
          input.user,
          restoreWorkspace(input.user.id, input.user.roles) ??
            deriveDefaultWorkspace(input.user.roles),
        ),
      );
      return true;
    },

    expireSession() {
      if (!hasAuthenticatedIdentity(state)) {
        return false;
      }

      accessTokenVault.invalidateOperations();
      accessTokenVault.clear();
      publish(EXPIRED_SESSION);
      return true;
    },

    getSnapshot() {
      return state;
    },

    markVerificationUnavailable(operationGeneration) {
      if (
        operationGeneration !== undefined &&
        !runtime.isCurrentOperation(operationGeneration)
      )
        return;
      if (operationGeneration === undefined)
        accessTokenVault.invalidateOperations();
      accessTokenVault.clear();
      publish(VERIFICATION_UNAVAILABLE_SESSION);
    },

    setVerificationPending(isVerifying, operationGeneration) {
      if (
        runtime.isCurrentOperation(operationGeneration) &&
        state.status === "verification-unavailable"
      ) {
        publish(
          Object.freeze({ status: "verification-unavailable", isVerifying }),
        );
      }
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

      // Persist the chosen persona so reloads (F5) keep this workspace.
      storeWorkspace(state.user.id, workspace);
      return true;
    },
  };

  return Object.freeze(runtime);
}
