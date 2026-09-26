import type { SessionRuntime } from "./session-runtime";
import type { RefreshOperation, RefreshResult } from "./refresh-result";

export interface RefreshCoordinator {
  refresh(): Promise<RefreshResult>;
}

export function createRefreshCoordinator(
  sessionRuntime: SessionRuntime,
  refreshOperation: RefreshOperation,
): RefreshCoordinator {
  let inFlightRefresh: Promise<RefreshResult> | null = null;

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

  return Object.freeze({
    refresh() {
      if (inFlightRefresh !== null) {
        return inFlightRefresh;
      }

      const refreshAttempt = performRefresh();
      inFlightRefresh = refreshAttempt;
      const clearInFlight = () => {
        if (inFlightRefresh === refreshAttempt) {
          inFlightRefresh = null;
        }
      };
      void refreshAttempt.then(clearInFlight, clearInFlight);
      return refreshAttempt;
    },
  });
}
