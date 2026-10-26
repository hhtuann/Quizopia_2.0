"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button, OUTLINE_LINK_CLASSES } from "../../../components/ui/button";
import { HeroOrb } from "../../../components/ui/hero-orb";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { StateBadge } from "../../../components/ui/state-badge";
import { Surface } from "../../../components/ui/surface";
import { formatInstant } from "../../../lib/i18n/core";
import { useI18n } from "../../../lib/i18n/provider";
import { assessmentFailureMessage } from "../../assessments/api/assessment-foundation";
import type {
  Notification,
  NotificationPage,
} from "../../assessments/api/publication-contract.generated";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import type { NotificationApiClient } from "../api/notification-api-client";
import { useNotificationClient } from "../hooks/use-notification-client";
import {
  confirmedCutoff,
  cutoffRemaining,
  missingCutoffLabel,
  noticeStatusLabels,
  noticeStatusVariants,
} from "../model/notification";

/**
 * Feature-level navigation hook for the Student Attempt experience. The
 * authorized attemptId is retained from the real notification; the destination
 * must exist and revalidate access before this backs an active link.
 */
export function studentAttemptHref(notification: Notification): string {
  return `/app/attempts/${notification.attemptId}`;
}

/**
 * Localized cutoff guidance composed from the same server-timestamp decision
 * as the canonical English `cutoffRemainingGuidance`.
 */
function useCutoffGuidance() {
  const { t } = useI18n();
  return (serverNow: string, closureCutoffAt: string): string | null => {
    const remaining = cutoffRemaining(serverNow, closureCutoffAt);
    if (remaining === null) return null;
    switch (remaining.kind) {
      case "reached":
        return t("the cutoff has been reached on the server");
      case "at-least-48h":
        return t("at least 48 hours remain (server guidance)");
      case "hours-minutes": {
        const parts: string[] = [
          t(remaining.hours === 1 ? "{count} hour" : "{count} hours", {
            count: remaining.hours,
          }),
        ];
        if (remaining.minutes > 0)
          parts.push(
            t(remaining.minutes === 1 ? "{count} minute" : "{count} minutes", {
              count: remaining.minutes,
            }),
          );
        return t("about {duration} remain (server guidance)", {
          duration: parts.join(" "),
        });
      }
      case "minutes":
        return t("about {duration} remain (server guidance)", {
          duration: t(
            remaining.minutes === 1 ? "{count} minute" : "{count} minutes",
            { count: remaining.minutes },
          ),
        });
      default:
        return t("less than a minute remains (server guidance)");
    }
  };
}

export function NotificationsPage() {
  return (
    <AuthenticatedBoundary>
      <StudentNotificationsPage />
    </AuthenticatedBoundary>
  );
}

function StudentNotificationsPage() {
  const { user, getSessionGeneration } = useAuth();
  const { t } = useI18n();
  const client = useNotificationClient();
  const scope = `${user?.id}:${getSessionGeneration()}`;
  return (
    <ApplicationShell currentSection="notifications">
      {!user?.roles.includes("STUDENT") ? (
        <Alert title={t("Student access required")} variant="warning">
          {t(
            "The assessment inbox belongs to the authenticated recipient with current Student authority.",
          )}{" "}
          <Link className="rounded py-2 font-semibold underline" href="/app">
            {t("Return to application")}
          </Link>
          .
        </Alert>
      ) : client === null ? (
        <Alert title={t("Inbox unavailable")} variant="warning">
          {t(
            "Your authenticated request service is unavailable. Sign in again before continuing.",
          )}
        </Alert>
      ) : (
        <InboxScreen client={client} key={scope} />
      )}
    </ApplicationShell>
  );
}

export function InboxScreen({
  client,
}: {
  readonly client: NotificationApiClient;
}) {
  const { t, localize, locale } = useI18n();
  const cutoffGuidance = useCutoffGuidance();
  const lifetime = useRef<AbortController | null>(null);
  const readController = useRef<AbortController | null>(null);
  const readSequence = useRef(0);
  const pageController = useRef<AbortController | null>(null);
  const pageSequence = useRef(0);
  const pageInFlight = useRef(false);
  const [items, setItems] = useState<readonly Notification[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [failureText, setFailureText] = useState<string | null>(null);

  const load = useCallback(
    (cursor: string | undefined): Promise<NotificationPage | null> => {
      const sequence = ++readSequence.current;
      readController.current?.abort();
      pageController.current?.abort();
      pageSequence.current += 1;
      pageInFlight.current = false;
      const controller = new AbortController();
      readController.current = controller;
      return client
        .list(cursor === undefined ? undefined : { cursor }, controller.signal)
        .then((result) => {
          if (
            controller.signal.aborted ||
            lifetime.current?.signal.aborted ||
            sequence !== readSequence.current
          )
            return null;
          setLoadingMore(false);
          setLoading(false);
          if (!result.ok) {
            setFailureText(assessmentFailureMessage(result.error));
            return null;
          }
          setFailureText(null);
          setItems(result.value.items);
          setNextCursor(result.value.nextCursor);
          return result.value;
        });
    },
    [client],
  );

  const loadMore = useCallback(
    async (cursor: string) => {
      if (pageInFlight.current || lifetime.current?.signal.aborted) return;
      pageInFlight.current = true;
      const sequence = ++pageSequence.current;
      pageController.current?.abort();
      const controller = new AbortController();
      pageController.current = controller;
      setLoadingMore(true);
      const page = await client.list({ cursor }, controller.signal);
      if (
        controller.signal.aborted ||
        lifetime.current?.signal.aborted ||
        sequence !== pageSequence.current
      )
        return;
      setLoadingMore(false);
      pageInFlight.current = false;
      if (!page.ok) {
        setFailureText(assessmentFailureMessage(page.error));
        return;
      }
      // Keyset pages: append the strictly older page below the current list.
      setItems((current) => {
        const seen = new Set(current.map((item) => item.notificationId));
        return [
          ...current,
          ...page.value.items.filter((item) => !seen.has(item.notificationId)),
        ];
      });
      setNextCursor(page.value.nextCursor);
    },
    [client],
  );

  useEffect(() => {
    const scope = new AbortController();
    lifetime.current = scope;
    void load(undefined);
    return () => {
      scope.abort();
      readController.current?.abort();
      pageController.current?.abort();
    };
  }, [load]);

  return (
    <div className="space-y-5">
      <header className="relative overflow-hidden rounded-2xl border border-primary/10 bg-surface p-6 shadow-card sm:p-8">
        <HeroOrb className="-right-10 -top-20 size-72" />
        <div className="relative flex flex-col gap-5 sm:flex-row sm:items-center sm:justify-between">
          <div className="min-w-0 max-w-2xl">
            <p className="text-sm font-semibold text-primary">
              {t("Assessment notifications")}
            </p>
            <h1 className="mt-1 font-heading text-3xl font-bold">
              {t("Your inbox")}
            </h1>
            <p className="mt-2 text-sm text-foreground-secondary">
              {t(
                "Durable closure notices addressed to your account. Reading a notice is not an acknowledgment and never starts or changes a 48-hour period; only the server's confirmed accessibility of your notice does.",
              )}
            </p>
          </div>
          <Button
            className="shrink-0"
            disabled={loadingMore}
            isLoading={loading}
            loadingLabel={t("Refreshing inbox")}
            onClick={() => {
              setLoading(true);
              void load(undefined);
            }}
            variant="secondary"
          >
            {t("Refresh inbox")}
          </Button>
        </div>
      </header>
      {failureText ? (
        <Alert title={t("Inbox could not be read")} variant="warning">
          {localize(failureText)}
        </Alert>
      ) : null}
      {loading ? (
        <Surface className="p-6">
          <div className="flex items-center gap-3" role="status">
            <LoadingIndicator label={t("Loading notifications")} />
            {t("Loading your assessment notices…")}
          </div>
        </Surface>
      ) : failureText === null && items.length === 0 ? (
        <Surface className="p-6">
          <h2 className="text-xl font-bold">
            {t("No assessment notices yet")}
          </h2>
          <p className="mt-2 text-sm text-foreground-secondary">
            {t(
              "When a Teacher requests delayed closure of an assessment you have an active attempt in, a durable notice appears here.",
            )}
          </p>
        </Surface>
      ) : (
        <ul
          aria-label={t("Assessment notifications")}
          className="grid gap-4 xl:grid-cols-2"
        >
          {items.map((item) => {
            const cutoff = confirmedCutoff(item);
            return (
              <li key={item.notificationId}>
                <Surface className="space-y-3 p-5">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <h2 className="min-w-0 break-words text-lg font-bold">
                      {item.publicationTitle}
                    </h2>
                    <StateBadge
                      variant={noticeStatusVariants[item.noticeStatus]}
                    >
                      {localize(noticeStatusLabels[item.noticeStatus])}
                    </StateBadge>
                  </div>
                  <dl className="grid gap-3 text-sm sm:grid-cols-2">
                    <div>
                      <dt className="text-xs font-medium text-foreground-muted">
                        {t("Published")}
                      </dt>
                      <dd className="mt-0.5">
                        {formatInstant(locale, item.publishedAt)}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-xs font-medium text-foreground-muted">
                        {cutoff === null
                          ? t("48-hour period")
                          : t("Confirmed cutoff (authoritative)")}
                      </dt>
                      <dd className="mt-0.5">
                        {cutoff === null
                          ? localize(missingCutoffLabel(item))
                          : formatInstant(locale, cutoff.closureCutoffAt, {
                              withTimeZone: true,
                            })}
                      </dd>
                    </div>
                  </dl>
                  {cutoff !== null &&
                  item.noticeStatus === "NOTICE_CONFIRMED" ? (
                    <p className="text-sm text-foreground-secondary">
                      {cutoffGuidance(item.serverNow, cutoff.closureCutoffAt) ??
                        t("Cutoff confirmed; refresh for current guidance.")}
                    </p>
                  ) : null}
                  <Link
                    className={OUTLINE_LINK_CLASSES}
                    href={`/app/notifications/${item.notificationId}`}
                  >
                    {t("View notice")}
                  </Link>
                </Surface>
              </li>
            );
          })}
        </ul>
      )}
      {nextCursor !== null ? (
        <div className="flex justify-center">
          <Button
            disabled={loading || loadingMore}
            isLoading={loadingMore}
            loadingLabel={t("Loading more notices")}
            onClick={() => void loadMore(nextCursor)}
            variant="secondary"
          >
            {t("Load more notices")}
          </Button>
        </div>
      ) : null}
    </div>
  );
}

export function NotificationDetailPage({
  notificationId,
}: {
  readonly notificationId: string;
}) {
  return (
    <AuthenticatedBoundary>
      <StudentNotificationDetailPage notificationId={notificationId} />
    </AuthenticatedBoundary>
  );
}

function StudentNotificationDetailPage({
  notificationId,
}: {
  readonly notificationId: string;
}) {
  const { user, getSessionGeneration } = useAuth();
  const { t } = useI18n();
  const client = useNotificationClient();
  const scope = `${user?.id}:${getSessionGeneration()}:${notificationId}`;
  return (
    <ApplicationShell currentSection="notifications">
      <div className="mb-6">
        <Link className={OUTLINE_LINK_CLASSES} href="/app/notifications">
          {t("Back to inbox")}
        </Link>
      </div>
      {!user?.roles.includes("STUDENT") ? (
        <Alert title={t("Student access required")} variant="warning">
          {t(
            "This notice belongs to its recipient with current Student authority.",
          )}{" "}
          <Link className="rounded py-2 font-semibold underline" href="/app">
            {t("Return to application")}
          </Link>
          .
        </Alert>
      ) : client === null ? (
        <Alert title={t("Notice unavailable")} variant="warning">
          {t(
            "Your authenticated request service is unavailable. Sign in again before continuing.",
          )}
        </Alert>
      ) : (
        <NotificationDetailScreen
          client={client}
          key={scope}
          notificationId={notificationId}
        />
      )}
    </ApplicationShell>
  );
}

export function NotificationDetailScreen({
  client,
  notificationId,
}: {
  readonly client: NotificationApiClient;
  readonly notificationId: string;
}) {
  const { t, localize, locale } = useI18n();
  const cutoffGuidance = useCutoffGuidance();
  const lifetime = useRef<AbortController | null>(null);
  const readController = useRef<AbortController | null>(null);
  const readSequence = useRef(0);
  const [notice, setNotice] = useState<Notification | null>(null);
  const [loading, setLoading] = useState(true);
  const [failureText, setFailureText] = useState<string | null>(null);

  const refresh = useCallback((): Promise<Notification | null> => {
    const sequence = ++readSequence.current;
    readController.current?.abort();
    const controller = new AbortController();
    readController.current = controller;
    return client.get(notificationId, controller.signal).then((result) => {
      if (
        controller.signal.aborted ||
        lifetime.current?.signal.aborted ||
        sequence !== readSequence.current
      )
        return null;
      setLoading(false);
      if (!result.ok) {
        setFailureText(assessmentFailureMessage(result.error));
        return null;
      }
      setFailureText(null);
      setNotice(result.value);
      return result.value;
    });
  }, [client, notificationId]);

  useEffect(() => {
    const scope = new AbortController();
    lifetime.current = scope;
    void refresh();
    return () => {
      scope.abort();
      readController.current?.abort();
    };
  }, [refresh]);

  if (notice === null)
    return (
      <Surface className="p-6">
        <h1 className="font-heading text-3xl font-bold">
          {t("Assessment notice")}
        </h1>
        {loading ? (
          <div className="mt-5 flex items-center gap-3" role="status">
            <LoadingIndicator label={t("Loading notice")} />
            {t("Loading your notice…")}
          </div>
        ) : (
          <>
            <Alert
              className="mt-5"
              title={t("Notice could not be loaded")}
              variant="warning"
            >
              {failureText
                ? localize(failureText)
                : t("This notification is unavailable.")}
            </Alert>
            <Button
              className="mt-4"
              onClick={() => {
                setLoading(true);
                void refresh();
              }}
              variant="secondary"
            >
              {t("Retry notice")}
            </Button>
          </>
        )}
      </Surface>
    );

  const cutoff = confirmedCutoff(notice);
  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-primary">
            {t("Assessment notice")}
          </p>
          <h1 className="mt-1 break-words font-heading text-3xl font-bold">
            {notice.publicationTitle}
          </h1>
          <div className="mt-3">
            <StateBadge variant={noticeStatusVariants[notice.noticeStatus]}>
              {localize(noticeStatusLabels[notice.noticeStatus])}
            </StateBadge>
          </div>
        </div>
        <Button
          isLoading={loading}
          loadingLabel={t("Refreshing notice")}
          onClick={() => {
            setLoading(true);
            void refresh();
          }}
          variant="secondary"
        >
          {t("Refresh notice")}
        </Button>
      </header>
      {failureText ? (
        <Alert title={t("Notice could not be refreshed")} variant="warning">
          {localize(failureText)}{" "}
          {t("Previously verified content remains visible below.")}
        </Alert>
      ) : null}
      <Surface className="space-y-4 p-5 sm:p-6">
        <h2 className="font-heading text-xl font-bold">
          {t("Notice message")}
        </h2>
        <p className="max-w-3xl break-words whitespace-pre-line text-sm leading-7 text-foreground-secondary">
          {notice.message}
        </p>
      </Surface>
      <Surface className="p-5 sm:p-6">
        <h2 className="mb-5 font-heading text-xl font-bold">
          {t("Authoritative timeline")}
        </h2>
        <dl className="grid gap-4 sm:grid-cols-2">
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Published")}
            </dt>
            <dd className="mt-1 text-sm">
              {formatInstant(locale, notice.publishedAt, {
                withTimeZone: true,
              })}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Notice confirmed")}
            </dt>
            <dd className="mt-1 text-sm">
              {notice.confirmedNoticeAt === null
                ? localize(missingCutoffLabel(notice))
                : formatInstant(locale, notice.confirmedNoticeAt, {
                    withTimeZone: true,
                  })}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("48-hour cutoff")}
            </dt>
            <dd className="mt-1 text-sm">
              {notice.closureCutoffAt === null
                ? localize(missingCutoffLabel(notice))
                : formatInstant(locale, notice.closureCutoffAt, {
                    withTimeZone: true,
                  })}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Server time of this response")}
            </dt>
            <dd className="mt-1 text-sm">
              {formatInstant(locale, notice.serverNow, { withTimeZone: true })}
            </dd>
          </div>
        </dl>
        {cutoff !== null && notice.noticeStatus === "NOTICE_CONFIRMED" ? (
          <p className="mt-4 text-sm font-medium text-foreground-secondary">
            {cutoffGuidance(notice.serverNow, cutoff.closureCutoffAt) ??
              t("Cutoff confirmed; refresh for current guidance.")}{" "}
            {t("The server timestamps above remain authoritative.")}
          </p>
        ) : null}
        {notice.noticeStatus === "PREPARING" ? (
          <Alert
            className="mt-4"
            title={t("Your notice is being prepared")}
            variant="info"
          >
            {t(
              "The 48-hour period starts only when the server confirms this notice is genuinely accessible for you. Until then there is no cutoff and no countdown; nothing is asked of you yet.",
            )}
          </Alert>
        ) : null}
        {notice.noticeStatus === "CANCELLED" ? (
          <Alert className="mt-4" title={t("Closure cancelled")} variant="info">
            {t(
              "The Teacher cancelled this closure request. This notice is retained as history and is no longer actionable.",
            )}
          </Alert>
        ) : null}
        {notice.noticeStatus === "SUBMITTED" ||
        notice.noticeStatus === "ABANDONED" ||
        notice.noticeStatus === "FINALIZED" ||
        notice.noticeStatus === "RESOLVED_EARLY" ||
        notice.noticeStatus === "CUTOFF_REACHED" ? (
          <Alert
            className="mt-4"
            title={
              notice.noticeStatus === "CUTOFF_REACHED"
                ? t("Cutoff reached; finalization pending")
                : t("Outcome recorded")
            }
            variant={
              notice.noticeStatus === "SUBMITTED" ||
              notice.noticeStatus === "FINALIZED"
                ? "success"
                : "info"
            }
          >
            {notice.noticeStatus === "SUBMITTED"
              ? t("You submitted this attempt yourself.")
              : notice.noticeStatus === "ABANDONED"
                ? t("You abandoned this attempt; it has no grade.")
                : notice.noticeStatus === "CUTOFF_REACHED"
                  ? t(
                      "The confirmed cutoff was reached. Refresh to track server finalization of eligible saved answers.",
                    )
                  : notice.noticeStatus === "FINALIZED"
                    ? t(
                        "Your saved answers were submitted and graded by the system.",
                      )
                    : t(
                        "This workflow was resolved without a cutoff finalization.",
                      )}
            {notice.resultId !== null
              ? ` ${t(
                  "A result exists for this attempt; use the Student results experience to view it.",
                )}`
              : ""}
          </Alert>
        ) : null}
      </Surface>
      <Surface className="p-5 sm:p-6">
        <h2 className="mb-4 font-heading text-xl font-bold">
          {t("References")}
        </h2>
        <dl className="grid gap-4 sm:grid-cols-2">
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Your attempt (authorized)")}
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              <Link
                className="font-semibold text-primary underline decoration-primary/40 underline-offset-4"
                href={studentAttemptHref(notice)}
              >
                {notice.attemptId}
              </Link>
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Closure request")}
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {notice.closureRequestId}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Publication")}
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {notice.publicationId}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Result (when finalized)")}
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {notice.resultId ?? t("Not available yet")}
            </dd>
          </div>
        </dl>
        <p className="mt-4 text-sm leading-6 text-foreground-muted">
          {t(
            "Opening your attempt or result always revalidates your current access on the server. This notice remains readable as your own history even if you leave the class or the assessment closes.",
          )}
        </p>
      </Surface>
    </div>
  );
}
