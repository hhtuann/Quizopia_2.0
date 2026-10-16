import { z } from "zod";
import { instantMicroseconds } from "../../assessments/utils/utc-time";
import type {
  MediaGrantContract,
  MediaGrantStatusContract,
} from "./proctoring-contract.derived";
import { canonicalUuid } from "./monitoring";

const instantString = z
  .string()
  .refine(
    (value) => instantMicroseconds(value) !== null,
    "Use a valid RFC3339 instant with an explicit offset.",
  );

/**
 * Scoped media grant response. `token` is a bearer secret: keep it in browser
 * memory only; never persist, log, cache or place it in URLs or analytics.
 * `technicalState: GRANT_ISSUED` proves a credential was signed — not that a
 * camera was permitted, connected or publishing.
 */
export const mediaGrantSchema = z
  .object({
    id: canonicalUuid,
    url: z.string().url(),
    token: z.string().min(1),
    leaseUntil: instantString,
    role: z.enum(["STUDENT", "TEACHER"]),
    technicalState: z.literal("GRANT_ISSUED"),
  })
  .strict() satisfies z.ZodType<MediaGrantContract>;

/** Durable local grant state; never contains a token. */
export const mediaGrantStatusSchema = z
  .object({
    id: canonicalUuid,
    leaseUntil: instantString,
    role: z.enum(["STUDENT", "TEACHER"]),
    status: z.enum(["PREPARED", "ACTIVE", "REVOKE_PENDING", "REVOKED"]),
  })
  .strict() satisfies z.ZodType<MediaGrantStatusContract>;

export type MediaGrant = MediaGrantContract;
export type MediaGrantStatus = MediaGrantStatusContract;

export type MediaGrantLifecycle = MediaGrantStatus["status"];

export interface MediaCleanupReport {
  readonly localTrack:
    | "not-applicable"
    | "not-present"
    | "retained-for-reauthorization"
    | "stopped"
    | "failed";
  readonly sdkDisconnect: "not-present" | "succeeded" | "failed";
  readonly backendRevoke:
    "not-requested" | "revoke-pending" | "revoked" | "failed";
}
