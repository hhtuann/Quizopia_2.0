import { z } from "zod";
import type {
  Notification as NotificationContract,
  NotificationPage,
} from "../../assessments/api/publication-contract.generated";
import {
  canonicalUuid,
  instantSchema,
} from "../../assessments/model/publication";
import { instantMicroseconds } from "../../assessments/utils/utc-time";

export type {
  Notification,
  NotificationPage,
} from "../../assessments/api/publication-contract.generated";

export type NoticeStatus = NotificationContract["noticeStatus"];

export const notificationSchema = z
  .object({
    notificationId: canonicalUuid,
    publicationId: canonicalUuid,
    publicationTitle: z.string().min(1).max(300),
    attemptId: canonicalUuid,
    closureRequestId: canonicalUuid,
    teacherInitiated: z.literal(true),
    contentVersion: z.literal(1),
    publishedAt: instantSchema,
    confirmedNoticeAt: instantSchema.nullable(),
    closureCutoffAt: instantSchema.nullable(),
    warningHours: z.literal(48),
    noticeStatus: z.enum([
      "PREPARING",
      "NOTICE_CONFIRMED",
      "CUTOFF_REACHED",
      "CANCELLED",
      "SUBMITTED",
      "ABANDONED",
      "FINALIZED",
      "RESOLVED_EARLY",
    ]),
    message: z.string().min(1),
    resultId: canonicalUuid.nullable(),
    serverNow: instantSchema,
  })
  .strict()
  .superRefine((value, context) => {
    // A cutoff exists only together with its confirmation, and the two must be
    // exactly 48 elapsed hours apart. Anything else is an untrustworthy
    // projection: fail closed rather than render a fabricated period.
    if (
      (value.confirmedNoticeAt === null) !==
      (value.closureCutoffAt === null)
    ) {
      context.addIssue({
        code: "custom",
        path: ["closureCutoffAt"],
        message: "Confirmation and cutoff must be present together.",
      });
      return;
    }
    if (value.confirmedNoticeAt !== null && value.closureCutoffAt !== null) {
      const confirmed = instantMicroseconds(value.confirmedNoticeAt);
      const cutoff = instantMicroseconds(value.closureCutoffAt);
      if (
        confirmed === null ||
        cutoff === null ||
        cutoff - confirmed !== BigInt(48) * BigInt(3_600_000_000)
      ) {
        context.addIssue({
          code: "custom",
          path: ["closureCutoffAt"],
          message: "Cutoff must be exactly 48 hours after confirmation.",
        });
      }
    }
    if (
      value.noticeStatus === "PREPARING" &&
      (value.confirmedNoticeAt !== null || value.closureCutoffAt !== null)
    ) {
      context.addIssue({
        code: "custom",
        path: ["noticeStatus"],
        message: "A preparing notice cannot establish a cutoff.",
      });
    }
    if (
      ["NOTICE_CONFIRMED", "CUTOFF_REACHED", "FINALIZED"].includes(
        value.noticeStatus,
      ) &&
      (value.confirmedNoticeAt === null || value.closureCutoffAt === null)
    ) {
      context.addIssue({
        code: "custom",
        path: ["noticeStatus"],
        message: "This notice state requires confirmed cutoff evidence.",
      });
    }
  }) satisfies z.ZodType<NotificationContract>;

export const notificationPageSchema = z
  .object({
    items: z.array(notificationSchema).max(100),
    nextCursor: z.string().min(1).max(1024).nullable(),
  })
  .strict() satisfies z.ZodType<NotificationPage>;

export const noticeStatusLabels: Record<NoticeStatus, string> = {
  PREPARING: "Notice preparing",
  NOTICE_CONFIRMED: "48-hour period confirmed",
  CUTOFF_REACHED: "Cutoff reached",
  CANCELLED: "Closure cancelled",
  SUBMITTED: "You submitted",
  ABANDONED: "You abandoned",
  FINALIZED: "Finalized by system",
  RESOLVED_EARLY: "Resolved early",
};

export const noticeStatusVariants: Record<
  NoticeStatus,
  "info" | "warning" | "success" | "neutral"
> = {
  PREPARING: "neutral",
  NOTICE_CONFIRMED: "warning",
  CUTOFF_REACHED: "warning",
  CANCELLED: "neutral",
  SUBMITTED: "success",
  ABANDONED: "neutral",
  FINALIZED: "success",
  RESOLVED_EARLY: "neutral",
};

/**
 * A confirmed cutoff exists only when the backend confirmed this recipient's
 * accessible notice. PREPARING never yields a period.
 */
export function confirmedCutoff(notification: NotificationContract): {
  readonly confirmedNoticeAt: string;
  readonly closureCutoffAt: string;
} | null {
  return notification.confirmedNoticeAt !== null &&
    notification.closureCutoffAt !== null
    ? {
        confirmedNoticeAt: notification.confirmedNoticeAt,
        closureCutoffAt: notification.closureCutoffAt,
      }
    : null;
}

export function missingCutoffLabel(notification: NotificationContract): string {
  return notification.noticeStatus === "PREPARING"
    ? "Not started — your notice is not confirmed yet"
    : "No confirmed cutoff was established";
}

/**
 * Guidance-only remaining time computed strictly from server timestamps
 * (cutoff minus serverNow). Browser clocks never move a cutoff.
 */
export function cutoffRemainingGuidance(
  serverNow: string,
  closureCutoffAt: string,
): string | null {
  const now = instantMicroseconds(serverNow);
  const cutoff = instantMicroseconds(closureCutoffAt);
  if (now === null || cutoff === null) return null;
  const remainingMicros = cutoff - now;
  if (remainingMicros <= BigInt(0))
    return "the cutoff has been reached on the server";
  const totalMinutes = Number(remainingMicros / BigInt(60_000_000));
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours >= 48) return "at least 48 hours remain (server guidance)";
  if (hours >= 1)
    return `about ${hours} hour${hours === 1 ? "" : "s"}${
      minutes === 0 ? "" : ` ${minutes} minute${minutes === 1 ? "" : "s"}`
    } remain (server guidance)`;
  if (totalMinutes >= 1)
    return `about ${totalMinutes} minute${
      totalMinutes === 1 ? "" : "s"
    } remain (server guidance)`;
  return "less than a minute remains (server guidance)";
}
