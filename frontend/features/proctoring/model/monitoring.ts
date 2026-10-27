import { z } from "zod";
import { instantMicroseconds } from "../../assessments/utils/utc-time";
import type {
  MonitoringAcknowledgmentContract,
  MonitoringErrorContract,
  MonitoringEventContract,
  MonitoringEvidenceContract,
  MonitoringSessionContract,
  MonitoringTimelinePageContract,
} from "./proctoring-contract.derived";

/**
 * The exact browser-observable event types from monitoring.openapi.yaml.
 *
 * SESSION_STARTED, SESSION_ENDED and ANSWER_PERSISTED are server-only and
 * intentionally absent: the browser must never send them.
 */
export const BROWSER_EVENT_TYPES = [
  "QUESTION_VIEWED",
  "QUESTION_LEFT",
  "ANSWER_SELECTED",
  "ANSWER_CHANGED",
  "ANSWER_CLEARED",
  "VISIBILITY_HIDDEN",
  "VISIBILITY_VISIBLE",
  "WINDOW_BLUR",
  "WINDOW_FOCUS",
  "FULLSCREEN_ENTERED",
  "FULLSCREEN_EXITED",
  "CAMERA_PERMISSION_GRANTED",
  "CAMERA_PERMISSION_DENIED",
  "DEVICE_MISSING",
  "DEVICE_ERROR",
  "CAMERA_UNAVAILABLE",
  "MEDIA_DISCONNECTED",
  "RECONNECTING",
  "NETWORK_DISCONNECTED",
  "NETWORK_RECONNECTED",
  "DUPLICATE_TAB",
] as const;

export type BrowserEventType = (typeof BROWSER_EVENT_TYPES)[number];

/** Every timeline type, including the server-only facts a Teacher may read. */
export const EVIDENCE_EVENT_TYPES = [
  ...BROWSER_EVENT_TYPES,
  "SESSION_STARTED",
  "SESSION_ENDED",
  "ANSWER_PERSISTED",
] as const;

export type EvidenceEventType = (typeof EVIDENCE_EVENT_TYPES)[number];

/** Question-scoped types require a positive integer questionNumber; others must omit it. */
export function isQuestionScoped(type: BrowserEventType): boolean {
  return type.startsWith("QUESTION_") || type.startsWith("ANSWER_");
}

export const canonicalUuid = z
  .string()
  .regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);

const instantString = z
  .string()
  .refine(
    (value) => instantMicroseconds(value) !== null,
    "Use a valid RFC3339 instant with an explicit offset.",
  );

export const monitoringSessionSchema = z
  .object({
    id: canonicalUuid,
    attemptId: canonicalUuid,
    publicationId: canonicalUuid,
    classroomId: canonicalUuid,
    studentId: canonicalUuid,
    deadlineAt: instantString,
    status: z.enum(["ACTIVE", "ENDED"]),
    createdAt: instantString,
    endedAt: instantString.nullable(),
  })
  .strict() satisfies z.ZodType<MonitoringSessionContract>;

export const monitoringErrorSchema = z
  .object({ code: z.string().regex(/^[A-Z_]{1,64}$/) })
  .strict() satisfies z.ZodType<MonitoringErrorContract>;

export const monitoringAcknowledgmentSchema = z
  .object({
    id: canonicalUuid,
    ordinal: z.number().int().min(1),
    receivedAt: instantString,
  })
  .strict() satisfies z.ZodType<MonitoringAcknowledgmentContract>;

export const monitoringEvidenceSchema = z
  .object({
    ordinal: z.number().int().min(1),
    id: canonicalUuid,
    streamId: canonicalUuid.nullable(),
    sequence: z.number().int().min(0).nullable(),
    type: z.enum(EVIDENCE_EVENT_TYPES),
    observedAt: instantString.nullable(),
    questionNumber: z.number().int().min(1).max(2_147_483_647).nullable(),
    receivedAt: instantString,
    source: z.enum([
      "CLIENT_OBSERVATION",
      "SERVER_AUTHORITY",
      "ASSESSMENT_AUTHORITY",
    ]),
    integritySha256: z.string().regex(/^[0-9a-f]{64}$/),
    schemaVersion: z.literal(1),
    actorId: canonicalUuid,
    assessmentRevision: z
      .string()
      .regex(/^[1-9][0-9]{0,18}$/)
      .refine(
        (value) => value.length < 19 || value <= "9223372036854775807",
        "Revision exceeds signed 64-bit range.",
      )
      .nullable(),
  })
  .strict() satisfies z.ZodType<MonitoringEvidenceContract>;

export const monitoringTimelinePageSchema = z
  .object({
    items: z.array(monitoringEvidenceSchema).max(100),
    nextAfter: z.number().int().min(1).nullable(),
  })
  .strict() satisfies z.ZodType<MonitoringTimelinePageContract>;

/** Exact emit-side envelope; see buildBrowserEvent for the single construction path. */
const browserEventSchema = z
  .object({
    eventId: canonicalUuid,
    streamId: canonicalUuid,
    sequence: z.number().int().min(0),
    type: z.enum(BROWSER_EVENT_TYPES),
    observedAt: instantString.nullable(),
    questionNumber: z
      .number()
      .int()
      .min(1)
      .max(2_147_483_647)
      .nullable()
      .optional(),
    schemaVersion: z.literal(1),
  })
  .strict()
  .superRefine((value, context) => {
    const hasQuestionNumber = (value.questionNumber ?? null) !== null;
    if (isQuestionScoped(value.type) !== hasQuestionNumber) {
      context.addIssue({
        code: "custom",
        path: ["questionNumber"],
        message: isQuestionScoped(value.type)
          ? "Question-scoped events require a positive questionNumber."
          : "Only question-scoped events may carry questionNumber.",
      });
    }
  }) satisfies z.ZodType<MonitoringEventContract>;

export interface BrowserEventInput {
  readonly type: BrowserEventType;
  readonly questionNumber?: number | undefined;
  readonly observedAt?: string | null;
}

export interface BuiltBrowserEvent {
  readonly eventId: string;
  readonly streamId: string;
  readonly sequence: number;
  readonly type: BrowserEventType;
  readonly observedAt: string | null;
  readonly questionNumber: number | null;
  readonly schemaVersion: 1;
}

/**
 * Validate a prepared browser event against the strict contract shape.
 * Invalid combinations are programming errors and must never reach the wire.
 */
export function validateBrowserEvent(event: BuiltBrowserEvent): boolean {
  return browserEventSchema.safeParse(event).success;
}

export type MonitoringSession = MonitoringSessionContract;
export type MonitoringEvidence = MonitoringEvidenceContract;
export type MonitoringTimelinePage = MonitoringTimelinePageContract;
export type MonitoringAcknowledgment = MonitoringAcknowledgmentContract;

/** Provenance labels for the Teacher timeline; neither kind is proof of misconduct. */
export const PROVENANCE_LABELS: Readonly<
  Record<MonitoringEvidence["source"], string>
> = {
  CLIENT_OBSERVATION: "Browser observation",
  SERVER_AUTHORITY: "Recorded session fact",
  ASSESSMENT_AUTHORITY: "Saved assessment fact",
};
