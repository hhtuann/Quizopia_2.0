import type { AuthenticatedSessionInput } from "./session-runtime";

export interface RefreshSucceeded {
  readonly status: "refreshed";
  readonly session: AuthenticatedSessionInput;
}

export interface RefreshFoundNoSession {
  readonly status: "no-session";
}

export interface RefreshFailedTransiently {
  readonly status: "transient-failure";
  readonly cause?: unknown;
}

export type RefreshResult =
  RefreshSucceeded | RefreshFoundNoSession | RefreshFailedTransiently;

export type RefreshOperation = () => Promise<RefreshResult>;
