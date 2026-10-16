import type { SessionRuntime } from "./session-runtime";
import type { RefreshOperation, RefreshResult } from "./refresh-result";

export interface RefreshCoordinator {
  bootstrap(): Promise<RefreshResult>;
  refresh(options?: { readonly explicit?: boolean }): Promise<RefreshResult>;
  waitForIdle(): Promise<void>;
}

export function createRefreshCoordinator(
  sessionRuntime: SessionRuntime,
  refreshOperation: RefreshOperation,
): RefreshCoordinator {
  let inFlightRefresh: Promise<RefreshResult> | null = null;
  let uncertainRefresh: {
    readonly sessionGeneration: number;
    readonly result: RefreshResult;
  } | null = null;

  async function performBootstrap(): Promise<RefreshResult> {
    const generation = sessionRuntime.getOperationGeneration();
    const sessionGeneration = sessionRuntime.getSessionGeneration();
    if (sessionRuntime.getSnapshot().status !== "bootstrapping") {
      return { status: "no-session" };
    }

    let result: RefreshResult;
    try {
      result = await refreshOperation(generation, sessionGeneration);
    } catch (cause) {
      result = { status: "transient-failure", cause };
    }

    if (
      !sessionRuntime.isCurrentOperation(generation) ||
      sessionRuntime.getSessionGeneration() !== sessionGeneration
    )
      return { status: "no-session" };

    if (result.status === "refreshed") {
      return sessionRuntime.establishAuthenticatedSession(
        result.session,
        generation,
      )
        ? result
        : { status: "no-session" };
    }

    if (result.status === "no-session") {
      sessionRuntime.completeBootstrapAsAnonymous();
    } else if (result.verificationPending) {
      sessionRuntime.markVerificationUnavailable(generation);
    } else {
      sessionRuntime.completeBootstrapAsUnavailable();
    }
    return result;
  }

  async function performRefresh(explicit: boolean): Promise<RefreshResult> {
    const generation = sessionRuntime.getOperationGeneration();
    const sessionGeneration = sessionRuntime.getSessionGeneration();
    if (!explicit && uncertainRefresh?.sessionGeneration === sessionGeneration)
      return uncertainRefresh.result;
    if (!sessionRuntime.beginRefreshing()) {
      return { status: "no-session" };
    }

    let result: RefreshResult;
    try {
      result = await refreshOperation(generation, sessionGeneration);
    } catch (cause) {
      result = { status: "transient-failure", cause };
    }

    if (
      !sessionRuntime.isCurrentOperation(generation) ||
      sessionRuntime.getSessionGeneration() !== sessionGeneration
    )
      return { status: "no-session" };

    if (result.status === "refreshed") {
      uncertainRefresh = null;
      return sessionRuntime.completeRefreshing(result.session)
        ? result
        : { status: "no-session" };
    }

    if (result.status === "no-session") {
      sessionRuntime.expireSession();
      return result;
    }

    // A failed response may follow a committed cookie rotation. Further 401s
    // must not automatically replay a potentially consumed credential.
    uncertainRefresh = { sessionGeneration, result };
    return sessionRuntime.recoverFromRefreshFailure()
      ? result
      : { status: "no-session" };
  }

  function singleFlight(
    operation: () => Promise<RefreshResult>,
  ): Promise<RefreshResult> {
    if (inFlightRefresh !== null) {
      return inFlightRefresh;
    }

    const refreshAttempt = operation();
    inFlightRefresh = refreshAttempt;
    const clearInFlight = () => {
      if (inFlightRefresh === refreshAttempt) {
        inFlightRefresh = null;
      }
    };
    void refreshAttempt.then(clearInFlight, clearInFlight);
    return refreshAttempt;
  }

  return Object.freeze({
    bootstrap() {
      return singleFlight(performBootstrap);
    },
    refresh(options?: { readonly explicit?: boolean }) {
      return singleFlight(() => performRefresh(options?.explicit === true));
    },
    async waitForIdle() {
      await inFlightRefresh;
    },
  });
}
