import type { SessionRuntime } from "./session-runtime";
import type { RefreshOperation, RefreshResult } from "./refresh-result";

export interface RefreshCoordinator {
  bootstrap(): Promise<RefreshResult>;
  refresh(): Promise<RefreshResult>;
  waitForIdle(): Promise<void>;
}

export function createRefreshCoordinator(
  sessionRuntime: SessionRuntime,
  refreshOperation: RefreshOperation,
): RefreshCoordinator {
  let inFlightRefresh: Promise<RefreshResult> | null = null;

  async function performBootstrap(): Promise<RefreshResult> {
    if (sessionRuntime.getSnapshot().status !== "bootstrapping") {
      return { status: "no-session" };
    }

    let result: RefreshResult;
    try {
      result = await refreshOperation();
    } catch (cause) {
      result = { status: "transient-failure", cause };
    }

    if (result.status === "refreshed") {
      return sessionRuntime.establishAuthenticatedSession(result.session)
        ? result
        : { status: "no-session" };
    }

    if (result.status === "no-session") {
      sessionRuntime.completeBootstrapAsAnonymous();
    } else {
      sessionRuntime.completeBootstrapAsUnavailable();
    }
    return result;
  }

  async function performRefresh(): Promise<RefreshResult> {
    if (!sessionRuntime.beginRefreshing()) {
      return { status: "no-session" };
    }

    let result: RefreshResult;
    try {
      result = await refreshOperation();
    } catch (cause) {
      result = { status: "transient-failure", cause };
    }

    if (result.status === "refreshed") {
      return sessionRuntime.completeRefreshing(result.session)
        ? result
        : { status: "no-session" };
    }

    if (result.status === "no-session") {
      sessionRuntime.expireSession();
      return result;
    }

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
    refresh() {
      return singleFlight(performRefresh);
    },
    async waitForIdle() {
      await inFlightRefresh;
    },
  });
}
