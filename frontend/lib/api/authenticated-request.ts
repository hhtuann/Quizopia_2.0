import type { AccessTokenVault } from "../../features/auth/session/access-token-vault";
import type { RefreshCoordinator } from "../../features/auth/session/refresh-coordinator";
import type { SessionRuntime } from "../../features/auth/session/session-runtime";
import {
  abortedTransportResult,
  type HttpRequest,
  type HttpResponse,
  type HttpTransport,
  type HttpTransportResult,
} from "./http-transport";

export interface ReplayableAuthenticatedRequest {
  /** Called once per attempt. It must return a fresh, safely replayable body. */
  readonly createRequest: () => Omit<HttpRequest, "signal">;
  /** Shared across attempts; aborting it never cancels the shared refresh. */
  readonly signal?: AbortSignal;
}

export type AuthenticationFailureReason =
  | "authorization-header-conflict"
  | "no-access-token"
  | "no-session"
  | "session-changed"
  | "unauthorized-after-retry";

export type AuthenticatedRequestResult =
  | HttpTransportResult
  | {
      readonly kind: "authentication-failure";
      readonly reason: AuthenticationFailureReason;
      readonly response?: HttpResponse;
    }
  | {
      readonly kind: "refresh-failure";
      readonly cause?: unknown;
    };

export interface AuthenticatedRequestExecutor {
  execute(
    request: ReplayableAuthenticatedRequest,
  ): Promise<AuthenticatedRequestResult>;
  executeOnce(
    request: ReplayableAuthenticatedRequest,
  ): Promise<AuthenticatedRequestResult>;
  executeOnceWithAccessToken(
    request: ReplayableAuthenticatedRequest,
    accessToken: string,
  ): Promise<AuthenticatedRequestResult>;
}

export function createAuthenticatedRequestExecutor(options: {
  readonly accessTokenVault: AccessTokenVault;
  readonly refreshCoordinator: RefreshCoordinator;
  readonly sessionRuntime: SessionRuntime;
  readonly transport: HttpTransport;
}): AuthenticatedRequestExecutor {
  const { accessTokenVault, refreshCoordinator, sessionRuntime, transport } =
    options;

  async function executeAttempt(
    request: ReplayableAuthenticatedRequest,
    accessToken: string,
  ): Promise<AuthenticatedRequestResult> {
    if (request.signal?.aborted) {
      return abortedTransportResult(request.signal.reason);
    }

    const attempt = request.createRequest();
    const headers = new Headers(attempt.headers);
    if (headers.has("authorization")) {
      return {
        kind: "authentication-failure",
        reason: "authorization-header-conflict",
      };
    }

    headers.set("authorization", `Bearer ${accessToken}`);
    return transport.execute({ ...attempt, headers, signal: request.signal });
  }

  function unauthorizedResponse(
    result: AuthenticatedRequestResult,
  ): HttpResponse | null {
    return result.kind === "response" && result.response.status === 401
      ? result.response
      : null;
  }

  async function retryOnce(
    request: ReplayableAuthenticatedRequest,
    accessToken: string,
    sessionGeneration: number,
  ): Promise<AuthenticatedRequestResult> {
    const retryResult = await executeAttempt(request, accessToken);
    const unauthorized = unauthorizedResponse(retryResult);
    if (unauthorized === null) {
      return retryResult;
    }

    const latest = accessTokenVault.readSnapshot();
    if (
      latest.sessionGeneration !== sessionGeneration ||
      latest.accessToken !== accessToken
    ) {
      return {
        kind: "authentication-failure",
        reason: "session-changed",
        response: unauthorized,
      };
    }

    sessionRuntime.expireSession();
    return {
      kind: "authentication-failure",
      reason: "unauthorized-after-retry",
      response: unauthorized,
    };
  }

  const executor: AuthenticatedRequestExecutor = {
    async execute(
      request: ReplayableAuthenticatedRequest,
    ): Promise<AuthenticatedRequestResult> {
      const initial = accessTokenVault.readSnapshot();
      if (initial.accessToken === null) {
        return {
          kind: "authentication-failure",
          reason: "no-access-token",
        };
      }

      const firstResult = await executeAttempt(request, initial.accessToken);
      if (unauthorizedResponse(firstResult) === null) {
        return firstResult;
      }

      if (request.signal?.aborted) {
        return abortedTransportResult(request.signal.reason);
      }

      const current = accessTokenVault.readSnapshot();
      if (current.sessionGeneration !== initial.sessionGeneration) {
        return {
          kind: "authentication-failure",
          reason: "session-changed",
        };
      }
      if (
        current.accessToken !== null &&
        current.accessToken !== initial.accessToken
      ) {
        return retryOnce(
          request,
          current.accessToken,
          current.sessionGeneration,
        );
      }

      const refreshResult = await refreshCoordinator.refresh();
      if (request.signal?.aborted) {
        return abortedTransportResult(request.signal.reason);
      }

      if (refreshResult.status === "no-session") {
        return {
          kind: "authentication-failure",
          reason: "no-session",
        };
      }

      if (refreshResult.status === "transient-failure") {
        return { kind: "refresh-failure", cause: refreshResult.cause };
      }

      const refreshed = accessTokenVault.readSnapshot();
      if (refreshed.sessionGeneration !== initial.sessionGeneration) {
        return {
          kind: "authentication-failure",
          reason: "session-changed",
        };
      }
      if (refreshed.accessToken === null) {
        return {
          kind: "authentication-failure",
          reason: "no-session",
        };
      }

      return retryOnce(
        request,
        refreshed.accessToken,
        refreshed.sessionGeneration,
      );
    },

    async executeOnce(request) {
      const accessToken = accessTokenVault.read();
      if (accessToken === null) {
        return {
          kind: "authentication-failure",
          reason: "no-access-token",
        };
      }

      return executeAttempt(request, accessToken);
    },

    executeOnceWithAccessToken(request, accessToken) {
      return executeAttempt(request, accessToken);
    },
  };

  return Object.freeze(executor);
}
