import { describe, expect, it } from "vitest";
import { evaluateMonitoringEligibility } from "./eligibility";
import {
  monitoringEvidenceSchema,
  validateBrowserEvent,
  type BuiltBrowserEvent,
} from "./monitoring";

const attemptId = "11111111-1111-4111-8111-111111111111";
const base = {
  attemptId,
  audience: "CLASS" as const,
  monitoringRequired: true,
  attemptStatus: "ACTIVE" as const,
  deadlineAt: "2026-10-16T12:00:00Z",
};

describe("monitoring eligibility boundary", () => {
  it("does not authorize absent, PUBLIC, disabled, unbounded or terminal Attempts", () => {
    expect(evaluateMonitoringEligibility(null)).toMatchObject({
      eligible: false,
    });
    expect(
      evaluateMonitoringEligibility({ ...base, audience: "PUBLIC" }),
    ).toEqual({
      eligible: false,
      reason: "public",
    });
    expect(
      evaluateMonitoringEligibility({ ...base, monitoringRequired: false }),
    ).toEqual({ eligible: false, reason: "not-enabled" });
    expect(
      evaluateMonitoringEligibility({ ...base, deadlineAt: null }),
    ).toEqual({
      eligible: false,
      reason: "unbounded",
    });
    expect(
      evaluateMonitoringEligibility({ ...base, attemptStatus: "SUBMITTED" }),
    ).toEqual({ eligible: false, reason: "not-active" });
  });

  it("allows only an eligible active bounded CLASS Attempt to ask the server", () => {
    expect(evaluateMonitoringEligibility(base)).toEqual({
      eligible: true,
      attemptId,
    });
  });
});

describe("strict event and evidence models", () => {
  const valid: BuiltBrowserEvent = {
    eventId: "22222222-2222-4222-8222-222222222222",
    streamId: "33333333-3333-4333-8333-333333333333",
    sequence: 0,
    type: "QUESTION_VIEWED",
    observedAt: "2026-10-16T10:00:00.000Z",
    questionNumber: 1,
    schemaVersion: 1,
  };

  it("requires question metadata without accepting answer content or server event types", () => {
    expect(validateBrowserEvent(valid)).toBe(true);
    expect(validateBrowserEvent({ ...valid, questionNumber: null })).toBe(
      false,
    );
    expect(
      validateBrowserEvent({
        ...valid,
        type: "VISIBILITY_HIDDEN",
        questionNumber: 1,
      }),
    ).toBe(false);
    expect(
      validateBrowserEvent({
        ...valid,
        type: "ANSWER_PERSISTED",
      } as unknown as BuiltBrowserEvent),
    ).toBe(false);
    expect(
      validateBrowserEvent({ ...valid, answer: "secret" } as BuiltBrowserEvent),
    ).toBe(false);
  });

  it("distinguishes authoritative persistence provenance and decimal revision", () => {
    const parsed = monitoringEvidenceSchema.safeParse({
      ordinal: 2,
      id: "22222222-2222-4222-8222-222222222222",
      streamId: null,
      sequence: null,
      type: "ANSWER_PERSISTED",
      observedAt: "2026-10-16T10:00:00Z",
      questionNumber: 4,
      receivedAt: "2026-10-16T10:00:01Z",
      source: "ASSESSMENT_AUTHORITY",
      integritySha256: "a".repeat(64),
      schemaVersion: 1,
      actorId: "44444444-4444-4444-8444-444444444444",
      assessmentRevision: "9223372036854775807",
    });
    expect(parsed.success).toBe(true);
    expect(
      monitoringEvidenceSchema.safeParse(
        parsed.success ? { ...parsed.data, assessmentRevision: 1 } : {},
      ).success,
    ).toBe(false);
    expect(
      monitoringEvidenceSchema.safeParse(
        parsed.success
          ? { ...parsed.data, assessmentRevision: "9223372036854775808" }
          : {},
      ).success,
    ).toBe(false);
    expect(
      monitoringEvidenceSchema.safeParse(
        parsed.success ? { ...parsed.data, questionNumber: 2_147_483_648 } : {},
      ).success,
    ).toBe(false);
  });
});
