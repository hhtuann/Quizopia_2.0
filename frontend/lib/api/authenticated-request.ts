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
  ): Promise<AuthenticatedRequestResult> {
    const retryResult = await executeAttempt(request, accessToken);
    const unauthorized = unauthorizedResponse(retryResult);
    if (unauthorized === null) {
      return retryResult;
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
      const tokenUsed = accessTokenVault.read();
      if (tokenUsed === null) {
        return {
          kind: "authentication-failure",
          reason: "no-access-token",
        };
      }

      const firstResult = await executeAttempt(request, tokenUsed);
      if (unauthorizedResponse(firstResult) === null) {
        return firstResult;
      }

      if (request.signal?.aborted) {
        return abortedTransportResult(request.signal.reason);
      }

      const currentToken = accessTokenVault.read();
      if (currentToken !== null && currentToken !== tokenUsed) {
        return retryOnce(request, currentToken);
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

      const refreshedToken = accessTokenVault.read();
      if (refreshedToken === null) {
        return {
          kind: "authentication-failure",
          reason: "no-session",
        };
      }

      return retryOnce(request, refreshedToken);
    },
  };

  return Object.freeze(executor);
}
