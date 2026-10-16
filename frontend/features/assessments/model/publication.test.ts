import { describe, expect, it } from "vitest";
import type { Configuration } from "../api/publication-contract.generated";
import {
  closedPublicationSchema,
  configurationSchema,
  newIdempotencyKey,
  ownerViewSchema,
  revisionSchema,
  sameConfiguration,
} from "./publication";

const configuration: Configuration = {
  title: "Published networking assessment",
  quizId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  quizVersionId: "9bd4c564-3c27-4e6d-91aa-a004334aa8f7",
  versionNumber: 3,
  audience: "PUBLIC",
  startAt: "2026-10-16T08:00:00Z",
  endAt: "2026-10-16T09:00:00Z",
  durationMinutes: 40,
  monitoringChoice: "DISABLED",
};
const publication = {
  publicationId: "e7b14962-3a2e-4d2d-926a-3b36ea90c199",
  configuration,
  state: "DRAFT",
  revision: 0,
  createdAt: "2026-10-16T07:00:00Z",
  openedAt: null,
  privateLocator: "A".repeat(43),
};

describe("Publication configuration contracts", () => {
  it.each([0, 5, 240])("accepts contract duration %i", (durationMinutes) => {
    expect(
      configurationSchema.safeParse({ ...configuration, durationMinutes })
        .success,
    ).toBe(true);
  });

  it.each([-1, 1, 4, 241, 5.5, null, "40"])(
    "rejects invalid duration %j",
    (durationMinutes) => {
      expect(
        configurationSchema.safeParse({ ...configuration, durationMinutes })
          .success,
      ).toBe(false);
    },
  );

  it.each([0, 1, 10, undefined])(
    "accepts maximum Attempts %j including legacy omission",
    (maxAttempts) => {
      expect(
        configurationSchema.safeParse({ ...configuration, maxAttempts })
          .success,
      ).toBe(true);
    },
  );

  it.each([-1, 11, 1.5, null, "1"])(
    "rejects maximum Attempts %j",
    (maxAttempts) => {
      expect(
        configurationSchema.safeParse({ ...configuration, maxAttempts })
          .success,
      ).toBe(false);
    },
  );

  it("requires one Classroom and an explicit monitoring state, without deriving authority", () => {
    const classroom = {
      ...configuration,
      audience: "CLASS",
      classroomId: configuration.quizId,
      monitoringChoice: "UNCONFIGURED",
    };
    expect(configurationSchema.safeParse(classroom).success).toBe(true);
    expect(
      configurationSchema.safeParse({ ...classroom, classroomId: null })
        .success,
    ).toBe(false);
    expect(
      configurationSchema.safeParse({
        ...classroom,
        classroomId: configuration.quizId.toUpperCase(),
      }).success,
    ).toBe(false);
    expect(
      configurationSchema.safeParse({
        ...classroom,
        monitoringChoice: undefined,
      }).success,
    ).toBe(false);
    expect(
      configurationSchema.safeParse({
        ...classroom,
        monitoringChoice: "ENABLED",
        durationMinutes: 0,
      }).success,
    ).toBe(false);
  });

  it("forbids Classroom associations and monitoring for PUBLIC", () => {
    expect(
      configurationSchema.safeParse({
        ...configuration,
        classroomId: configuration.quizId,
      }).success,
    ).toBe(false);
    expect(
      configurationSchema.safeParse({
        ...configuration,
        monitoringChoice: "ENABLED",
      }).success,
    ).toBe(false);
    expect(
      configurationSchema.safeParse({
        ...configuration,
        monitoringChoice: "UNCONFIGURED",
      }).success,
    ).toBe(false);
  });

  it("compares schedule instants at microsecond precision and accepts equivalent offsets", () => {
    expect(
      configurationSchema.safeParse({
        ...configuration,
        startAt: "2026-10-16T08:00:00.000001Z",
        endAt: "2026-10-16T08:00:00.000002Z",
      }).success,
    ).toBe(true);
    expect(
      configurationSchema.safeParse({
        ...configuration,
        startAt: "2026-10-16T08:00:00Z",
        endAt: "2026-10-16T15:00:00+07:00",
      }).success,
    ).toBe(false);
  });

  it("rejects blank or excessive text, unknown owner fields and invented source identity", () => {
    for (const change of [
      { title: "   " },
      { title: "a".repeat(301) },
      { description: "a".repeat(5001) },
      { ownerId: configuration.quizId },
      { quizVersionId: "latest" },
      { versionNumber: 0 },
    ]) {
      expect(
        configurationSchema.safeParse({ ...configuration, ...change }).success,
      ).toBe(false);
    }
  });

  it("compares effective defaults independently of property insertion order", () => {
    const reordered = {
      description: null,
      maxAttempts: 1,
      classroomId: null,
      ...configuration,
    };
    expect(sameConfiguration(configuration, reordered)).toBe(true);
    expect(
      sameConfiguration(configuration, {
        ...configuration,
        durationMinutes: 41,
      }),
    ).toBe(false);
  });

  it("compares equivalent wire offsets exactly without dropping microseconds", () => {
    expect(
      sameConfiguration(configuration, {
        ...configuration,
        startAt: "2026-10-16T15:00:00+07:00",
        endAt: "2026-10-16T16:00:00+07:00",
      }),
    ).toBe(true);
    expect(
      sameConfiguration(configuration, {
        ...configuration,
        endAt: "2026-10-16T09:00:00.000001Z",
      }),
    ).toBe(false);
    expect(
      sameConfiguration(configuration, {
        ...configuration,
        startAt: "invalid",
      }),
    ).toBe(false);
    expect(
      sameConfiguration(
        { ...configuration, startAt: "invalid" },
        { ...configuration, startAt: "invalid" },
      ),
    ).toBe(false);
  });

  it("creates random operation identities with the exact accepted alphabet and length", () => {
    const keys = new Set(Array.from({ length: 8 }, () => newIdempotencyKey()));
    expect(keys.size).toBe(8);
    for (const key of keys) expect(key).toMatch(/^[A-Za-z0-9_-]{16,128}$/);
  });
});

describe("Publication authority response validation", () => {
  it("accepts exact owner metadata and minimal ordinary CLOSE acknowledgement", () => {
    expect(ownerViewSchema.safeParse(publication).success).toBe(true);
    expect(
      closedPublicationSchema.safeParse({
        publicationId: publication.publicationId,
        state: "CLOSED",
        revision: 2,
        closedAt: "2026-10-16T08:30:00Z",
      }).success,
    ).toBe(true);
  });

  it.each([-1, 0.5, Number.MAX_SAFE_INTEGER + 1, "2", null])(
    "rejects lossy concurrency revision %j",
    (revision) => {
      expect(revisionSchema.safeParse(revision).success).toBe(false);
      expect(
        ownerViewSchema.safeParse({ ...publication, revision }).success,
      ).toBe(false);
    },
  );

  it("rejects answer-bearing or actor fields rather than rendering unintended projections", () => {
    for (const field of [
      "ownerId",
      "questions",
      "sourceSnapshot",
      "correctAnswers",
      "gradingPolicyVersion",
    ]) {
      expect(
        ownerViewSchema.safeParse({ ...publication, [field]: "private" })
          .success,
      ).toBe(false);
    }
  });

  it("requires finalized metadata and consistent private locator audience", () => {
    expect(
      ownerViewSchema.safeParse({ ...publication, state: "OPEN" }).success,
    ).toBe(false);
    expect(
      ownerViewSchema.safeParse({ ...publication, privateLocator: "invalid" })
        .success,
    ).toBe(false);
    expect(
      ownerViewSchema.safeParse({ ...publication, privateLocator: null })
        .success,
    ).toBe(false);
    expect(
      ownerViewSchema.safeParse({
        ...publication,
        configuration: {
          ...configuration,
          audience: "CLASS",
          classroomId: configuration.quizId,
        },
      }).success,
    ).toBe(false);
    expect(
      ownerViewSchema.safeParse({
        ...publication,
        state: "OPEN",
        openedAt: "2026-10-16T07:30:00Z",
      }).success,
    ).toBe(true);
  });
});
