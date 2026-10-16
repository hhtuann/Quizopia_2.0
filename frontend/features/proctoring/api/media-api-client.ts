import type { AuthenticatedRequestExecutor } from "../../../lib/api/authenticated-request";
import {
  mediaGrantSchema,
  mediaGrantStatusSchema,
  type MediaGrant,
  type MediaGrantStatus,
} from "../model/media";
import { canonicalUuid } from "../model/monitoring";
import {
  failed,
  runProctoringRequest,
  type ProctoringResult,
} from "./proctoring-request";

const MEDIA_BASE = "/api/proctoring/monitoring/sessions";

/**
 * Browser client for the implemented M2 scoped media-grant operations.
 *
 * All four operations are bodyless and query-free; the backend derives the
 * actor role and every room/participant binding. The browser never receives
 * or sends LiveKit administrative credentials.
 */
export interface MediaApiClient {
  /** Own STUDENT publish grant or authorized TEACHER subscribe grant. */
  createGrant(
    sessionId: string,
    signal?: AbortSignal,
  ): Promise<ProctoringResult<MediaGrant>>;
  /** Durable local status; the response never contains a token. */
  readGrant(
    sessionId: string,
    grantId: string,
    signal?: AbortSignal,
  ): Promise<ProctoringResult<MediaGrantStatus>>;
  /** Renew an own ACTIVE unexpired grant; returns a freshly signed token. */
  renewGrant(
    sessionId: string,
    grantId: string,
    signal?: AbortSignal,
  ): Promise<ProctoringResult<MediaGrant>>;
  /** Persist an own irreversible revocation intent; provider effect is reconciled server-side. */
  revokeGrant(
    sessionId: string,
    grantId: string,
    signal?: AbortSignal,
  ): Promise<ProctoringResult<MediaGrantStatus>>;
}

export function createMediaApiClient(options: {
  readonly authenticatedRequests: AuthenticatedRequestExecutor;
  readonly getSessionGeneration: () => number | null;
  readonly apiBaseUrl?: string;
}): MediaApiClient {
  const { authenticatedRequests, getSessionGeneration, apiBaseUrl } = options;
  const boundGeneration = getSessionGeneration();
  if (boundGeneration === null)
    throw new Error("Media client requires an authenticated session.");

  const uuid = (value: string) =>
    canonicalUuid.safeParse(value).success ? value : null;

  const run = <T>(request: {
    readonly method: "GET" | "POST";
    readonly path: string;
    readonly schema: {
      safeParse(input: unknown): { success: boolean; data?: T };
    };
    readonly signal?: AbortSignal;
  }) =>
    runProctoringRequest<T>({
      authenticatedRequests,
      getSessionGeneration,
      boundGeneration,
      apiBaseUrl,
      ...request,
    });

  const grantPath = (sessionId: string, suffix = "") =>
    `${MEDIA_BASE}/${sessionId}/media-grants${suffix}`;

  return {
    async createGrant(sessionId, signal) {
      const id = uuid(sessionId);
      if (id === null) return failed({ kind: "invalid-input" });
      return run<MediaGrant>({
        method: "POST",
        path: grantPath(id),
        schema: mediaGrantSchema,
        signal,
      });
    },
    async readGrant(sessionId, grantId, signal) {
      const ids = [uuid(sessionId), uuid(grantId)];
      if (ids.some((value) => value === null))
        return failed({ kind: "invalid-input" });
      return run<MediaGrantStatus>({
        method: "GET",
        path: grantPath(sessionId, `/${grantId}`),
        schema: mediaGrantStatusSchema,
        signal,
      });
    },
    async renewGrant(sessionId, grantId, signal) {
      const ids = [uuid(sessionId), uuid(grantId)];
      if (ids.some((value) => value === null))
        return failed({ kind: "invalid-input" });
      return run<MediaGrant>({
        method: "POST",
        path: grantPath(sessionId, `/${grantId}/renew`),
        schema: mediaGrantSchema,
        signal,
      });
    },
    async revokeGrant(sessionId, grantId, signal) {
      const ids = [uuid(sessionId), uuid(grantId)];
      if (ids.some((value) => value === null))
        return failed({ kind: "invalid-input" });
      return run<MediaGrantStatus>({
        method: "POST",
        path: grantPath(sessionId, `/${grantId}/revoke`),
        schema: mediaGrantStatusSchema,
        signal,
      });
    },
  };
}
