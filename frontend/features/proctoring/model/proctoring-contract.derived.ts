/**
 * Hand-derived frontend types for the implemented W3-M Proctoring contracts.
 *
 * Sources (authoritative; verified against the implemented controllers at
 * repository revision e5fbf50fda2389dcd72499ef4979df0217ef8f30):
 * - services/proctoring-service/contracts/monitoring.openapi.yaml (v1.0.0)
 * - services/proctoring-service/contracts/media.openapi.yaml (v1.0.0)
 *
 * Unlike `publication-contract.generated.ts`, these types are hand-derived
 * because no generator exists for the proctoring contracts. Runtime shapes
 * are additionally enforced by the zod schemas in `../model/monitoring.ts`
 * and `../model/media.ts`; keep all three files in sync with the contracts.
 */

/** `Session` from monitoring.openapi.yaml. Bindings are server-derived only. */
export interface MonitoringSessionContract {
  readonly id: string;
  readonly attemptId: string;
  readonly publicationId: string;
  readonly classroomId: string;
  readonly studentId: string;
  readonly deadlineAt: string;
  readonly status: "ACTIVE" | "ENDED";
  readonly createdAt: string;
  readonly endedAt: string | null;
}

/**
 * `Event` request body for POST /sessions/{id}/events.
 *
 * `sequence` and `questionNumber` must serialize as integral JSON tokens.
 * `schemaVersion` is always the integer 1. Server-only types
 * (SESSION_STARTED, SESSION_ENDED, ANSWER_PERSISTED) are never representable
 * here on purpose; see `BrowserEventType`.
 */
export interface MonitoringEventContract {
  readonly eventId: string;
  readonly streamId: string;
  readonly sequence: number;
  readonly type: string;
  readonly observedAt: string | null;
  readonly questionNumber?: number | null;
  readonly schemaVersion: 1;
}

/** `Acknowledgment` from appendMonitoringEvent. */
export interface MonitoringAcknowledgmentContract {
  readonly id: string;
  readonly ordinal: number;
  readonly receivedAt: string;
}

/**
 * `Evidence` timeline row returned to authorized Teachers.
 *
 * `source` distinguishes untrusted browser observations from server
 * authority facts; neither category proves misconduct.
 * `assessmentRevision` is a nullable decimal string populated only for
 * server-only ANSWER_PERSISTED facts from ASSESSMENT_AUTHORITY.
 */
export interface MonitoringEvidenceContract {
  readonly ordinal: number;
  readonly id: string;
  readonly streamId: string | null;
  readonly sequence: number | null;
  readonly type: string;
  readonly observedAt: string | null;
  readonly questionNumber: number | null;
  readonly receivedAt: string;
  readonly source:
    "CLIENT_OBSERVATION" | "SERVER_AUTHORITY" | "ASSESSMENT_AUTHORITY";
  readonly integritySha256: string;
  readonly schemaVersion: 1;
  readonly actorId: string;
  readonly assessmentRevision: string | null;
}

/** Keyset `Page` from readMonitoringTimeline. */
export interface MonitoringTimelinePageContract {
  readonly items: readonly MonitoringEvidenceContract[];
  readonly nextAfter: number | null;
}

/** Sanitized monitoring error body: a single uppercase code. */
export interface MonitoringErrorContract {
  readonly code: string;
}

/** `Grant` from media.openapi.yaml. `token` is a scoped bearer secret. */
export interface MediaGrantContract {
  readonly id: string;
  readonly url: string;
  readonly token: string;
  readonly leaseUntil: string;
  readonly role: "STUDENT" | "TEACHER";
  readonly technicalState: "GRANT_ISSUED";
}

/** `Status` from readMediaGrant/revokeMediaGrant; never contains a token. */
export interface MediaGrantStatusContract {
  readonly id: string;
  readonly leaseUntil: string;
  readonly role: "STUDENT" | "TEACHER";
  readonly status: "PREPARED" | "ACTIVE" | "REVOKE_PENDING" | "REVOKED";
}

/** Create-session request body: the attemptId only; every other binding is server-derived. */
export interface CreateMonitoringSessionContract {
  readonly attemptId: string;
}
