import { z } from "zod";
import type {
  Configuration,
  OwnerView,
  ClosedPublication,
} from "../api/publication-contract.generated";
import { instantMicroseconds } from "../utils/utc-time";

export const canonicalUuid = z
  .string()
  .regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
export const revisionSchema = z
  .number()
  .int()
  .nonnegative()
  .max(Number.MAX_SAFE_INTEGER);
export const instantSchema = z
  .string()
  .refine(
    (value) => instantMicroseconds(value) !== null,
    "Use a valid instant with an explicit offset and at most six fractional digits.",
  );
export const durationSchema = z.union([
  z.literal(0),
  z.number().int().min(5).max(240),
]);

export const configurationSchema = z
  .object({
    title: z
      .string()
      .min(1)
      .max(300)
      .refine((value) => value.trim().length > 0, "Enter a title."),
    description: z.string().max(5000).nullable().optional(),
    quizId: canonicalUuid,
    quizVersionId: canonicalUuid,
    versionNumber: z.number().int().min(1).max(999999999),
    audience: z.enum(["CLASS", "PUBLIC"]),
    classroomId: canonicalUuid.nullable().optional(),
    startAt: instantSchema,
    endAt: instantSchema,
    durationMinutes: durationSchema,
    monitoringChoice: z.enum(["UNCONFIGURED", "ENABLED", "DISABLED"]),
    maxAttempts: z.number().int().min(0).max(10).optional(),
  })
  .strict()
  .superRefine((value, context) => {
    const start = instantMicroseconds(value.startAt);
    const end = instantMicroseconds(value.endAt);
    if (start !== null && end !== null && start >= end)
      context.addIssue({
        code: "custom",
        path: ["endAt"],
        message: "Participation end must be after start.",
      });
    if (value.audience === "CLASS" && !value.classroomId)
      context.addIssue({
        code: "custom",
        path: ["classroomId"],
        message: "Enter one canonical Classroom ID.",
      });
    if (
      value.audience === "PUBLIC" &&
      (value.classroomId != null || value.monitoringChoice !== "DISABLED")
    )
      context.addIssue({
        code: "custom",
        path: ["monitoringChoice"],
        message: "PUBLIC has no Classroom and requires monitoring disabled.",
      });
    if (value.durationMinutes === 0 && value.monitoringChoice === "ENABLED")
      context.addIssue({
        code: "custom",
        path: ["monitoringChoice"],
        message: "Unlimited Attempts cannot enable monitoring.",
      });
  }) satisfies z.ZodType<Configuration>;

export const ownerViewSchema = z
  .object({
    publicationId: canonicalUuid,
    configuration: configurationSchema,
    state: z.enum(["DRAFT", "OPEN", "CLOSED"]),
    revision: revisionSchema,
    createdAt: instantSchema,
    openedAt: instantSchema.nullable(),
    privateLocator: z
      .string()
      .regex(/^[A-Za-z0-9_-]{43}$/)
      .nullable(),
  })
  .strict()
  .superRefine((value, context) => {
    if (
      (value.configuration.audience === "CLASS" &&
        value.privateLocator !== null) ||
      (value.configuration.audience === "PUBLIC" &&
        value.privateLocator === null)
    )
      context.addIssue({
        code: "custom",
        path: ["privateLocator"],
        message: "Inconsistent audience metadata.",
      });
    if (
      value.state !== "DRAFT" &&
      (value.openedAt === null ||
        value.configuration.monitoringChoice === "UNCONFIGURED")
    )
      context.addIssue({
        code: "custom",
        path: ["state"],
        message: "Incomplete finalized metadata.",
      });
  }) satisfies z.ZodType<OwnerView>;

export const closedPublicationSchema = z
  .object({
    publicationId: canonicalUuid,
    state: z.literal("CLOSED"),
    revision: revisionSchema,
    closedAt: instantSchema,
  })
  .strict() satisfies z.ZodType<ClosedPublication>;

export type PublicationConfiguration = Configuration;
export type Publication = OwnerView;

/**
 * Semantic StateBadge variants for server-returned Publication states.
 * Presentation only; labels always come from the authoritative state.
 */
export const publicationStateVariants: Record<
  "DRAFT" | "OPEN" | "CLOSED",
  "info" | "success" | "warning" | "neutral"
> = {
  DRAFT: "neutral",
  OPEN: "success",
  CLOSED: "info",
};

export function newIdempotencyKey(): string {
  const bytes = new Uint8Array(32);
  globalThis.crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
}

export function sameConfiguration(
  left: Configuration,
  right: Configuration,
): boolean {
  const fields = [
    "title",
    "quizId",
    "quizVersionId",
    "versionNumber",
    "audience",
    "durationMinutes",
    "monitoringChoice",
  ] as const;
  return (
    fields.every((field) => left[field] === right[field]) &&
    (left.description ?? null) === (right.description ?? null) &&
    (left.classroomId ?? null) === (right.classroomId ?? null) &&
    (left.maxAttempts ?? 1) === (right.maxAttempts ?? 1) &&
    (["startAt", "endAt"] as const).every((field) => {
      const instant = instantMicroseconds(left[field]);
      return instant !== null && instant === instantMicroseconds(right[field]);
    })
  );
}
