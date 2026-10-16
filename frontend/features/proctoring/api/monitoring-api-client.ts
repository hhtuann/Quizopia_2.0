import type { AuthenticatedRequestExecutor } from "../../../lib/api/authenticated-request";
import {
  canonicalUuid,
  monitoringAcknowledgmentSchema,
  monitoringSessionSchema,
  monitoringTimelinePageSchema,
  type BrowserEventType,
  type BuiltBrowserEvent,
  type MonitoringAcknowledgment,
  type MonitoringSession,
  type MonitoringTimelinePage,
} from "../model/monitoring";
import {
  failed,
  runProctoringRequest,
  type ProctoringResult,
} from "./proctoring-request";

const MONITORING_BASE = "/api/proctoring/monitoring/sessions";

export interface TimelineQuery {
  /** Server ordinal cursor (keyset pagination); defaults to 0. */
  readonly after?: number;
  /** 1..100 per the contract; defaults to 50 server-side. */
  readonly limit?: number;
}

/**
 * Browser client for the implemented M1 monitoring operations.
 * Every binding (Publication/Classroom/Student/role) is derived server-side;
 * the browser only ever supplies identifiers it legitimately holds.
 */
export interface MonitoringApiClient {
  /** STUDENT: create (or receive the existing durable) session for an own Attempt. */
  createSession(
    attemptId: string,
    signal?: AbortSignal,
  ): Promise<ProctoringResult<MonitoringSession>>;
  /** STUDENT owner or authorized TEACHER. */
  getSession(
    sessionId: string,
    signal?: AbortSignal,
  ): Promise<ProctoringResult<MonitoringSession>>;
  /** TEACHER only: find-by-attempt never creates; inaccessible attempts mask as 404. */
  findByAttempt(
    attemptId: string,
    signal?: AbortSignal,
  ): Promise<ProctoringResult<MonitoringSession>>;
  /** STUDENT owner: append one exact browser observation. */
  appendEvent(
    sessionId: string,
    event: BuiltBrowserEvent,
    signal?: AbortSignal,
  ): Promise<ProctoringResult<MonitoringAcknowledgment>>;
  /** TEACHER: bounded keyset timeline page. */
  timeline(
    sessionId: string,
    query?: TimelineQuery,
    signal?: AbortSignal,
  ): Promise<ProctoringResult<MonitoringTimelinePage>>;
  /** STUDENT owner or TEACHER: authoritative session reconciliation. */
  reconcile(
    sessionId: string,
    signal?: AbortSignal,
  ): Promise<ProctoringResult<MonitoringSession>>;
}

export function createMonitoringApiClient(options: {
  readonly authenticatedRequests: AuthenticatedRequestExecutor;
  readonly getSessionGeneration: () => number | null;
  readonly apiBaseUrl?: string;
}): MonitoringApiClient {
  const { authenticatedRequests, getSessionGeneration, apiBaseUrl } = options;
  const boundGeneration = getSessionGeneration();
  if (boundGeneration === null)
    throw new Error("Proctoring client requires an authenticated session.");

  const run = <T>(request: {
    readonly method: "GET" | "POST";
    readonly path: string;
    readonly body?: unknown;
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

  const uuid = (value: string) =>
    canonicalUuid.safeParse(value).success ? value : null;

  return {
    async createSession(attemptId, signal) {
      const id = uuid(attemptId);
      if (id === null) return failed({ kind: "invalid-input" });
      return run<MonitoringSession>({
        method: "POST",
        path: MONITORING_BASE,
        body: { attemptId: id },
        schema: monitoringSessionSchema,
        signal,
      });
    },
    async getSession(sessionId, signal) {
      const id = uuid(sessionId);
      if (id === null) return failed({ kind: "invalid-input" });
      return run<MonitoringSession>({
        method: "GET",
        path: `${MONITORING_BASE}/${id}`,
        schema: monitoringSessionSchema,
        signal,
      });
    },
    async findByAttempt(attemptId, signal) {
      const id = uuid(attemptId);
      if (id === null) return failed({ kind: "invalid-input" });
      return run<MonitoringSession>({
        method: "GET",
        path: `${MONITORING_BASE}/by-attempt/${id}`,
        schema: monitoringSessionSchema,
        signal,
      });
    },
    async appendEvent(sessionId, event, signal) {
      const id = uuid(sessionId);
      if (id === null) return failed({ kind: "invalid-input" });
      return run<MonitoringAcknowledgment>({
        method: "POST",
        path: `${MONITORING_BASE}/${id}/events`,
        body: event,
        schema: monitoringAcknowledgmentSchema,
        signal,
      });
    },
    async timeline(sessionId, query, signal) {
      const id = uuid(sessionId);
      if (id === null) return failed({ kind: "invalid-input" });
      const after = query?.after ?? 0;
      const limit = query?.limit;
      if (
        !Number.isInteger(after) ||
        after < 0 ||
        (limit !== undefined &&
          (!Number.isInteger(limit) || limit < 1 || limit > 100))
      )
        return failed({ kind: "invalid-input" });
      const search =
        after === 0 && limit === undefined
          ? ""
          : `?after=${after}${limit === undefined ? "" : `&limit=${limit}`}`;
      return run<MonitoringTimelinePage>({
        method: "GET",
        path: `${MONITORING_BASE}/${id}/events${search}`,
        schema: monitoringTimelinePageSchema,
        signal,
      });
    },
    async reconcile(sessionId, signal) {
      const id = uuid(sessionId);
      if (id === null) return failed({ kind: "invalid-input" });
      return run<MonitoringSession>({
        method: "POST",
        path: `${MONITORING_BASE}/${id}/reconcile`,
        schema: monitoringSessionSchema,
        signal,
      });
    },
  };
}

/** Timeline type-to-copy mapping shared by the Teacher UI; never implies misconduct. */
export function describeEvidenceType(type: BrowserEventType | string): string {
  const labels: Record<string, string> = {
    QUESTION_VIEWED: "Question opened",
    QUESTION_LEFT: "Question left",
    ANSWER_SELECTED: "Answer selected (browser report)",
    ANSWER_CHANGED: "Answer changed (browser report)",
    ANSWER_CLEARED: "Answer cleared (browser report)",
    VISIBILITY_HIDDEN: "Tab hidden",
    VISIBILITY_VISIBLE: "Tab visible",
    WINDOW_BLUR: "Window lost focus",
    WINDOW_FOCUS: "Window focused",
    FULLSCREEN_ENTERED: "Fullscreen entered",
    FULLSCREEN_EXITED: "Fullscreen exited",
    CAMERA_PERMISSION_GRANTED: "Camera permission granted",
    CAMERA_PERMISSION_DENIED: "Camera permission denied",
    DEVICE_MISSING: "No camera device found",
    DEVICE_ERROR: "Camera device error",
    CAMERA_UNAVAILABLE: "Camera unavailable",
    MEDIA_DISCONNECTED: "Media disconnected",
    RECONNECTING: "Reconnecting",
    NETWORK_DISCONNECTED: "Network disconnected",
    NETWORK_RECONNECTED: "Network reconnected",
    DUPLICATE_TAB: "Duplicate attempt tab detected",
    SESSION_STARTED: "Monitoring session started",
    SESSION_ENDED: "Monitoring session ended",
    ANSWER_PERSISTED: "Answer persisted (Assessment fact)",
  };
  return labels[type] ?? type;
}
