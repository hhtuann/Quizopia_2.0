"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button, OUTLINE_LINK_CLASSES } from "../../../components/ui/button";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { StateBadge } from "../../../components/ui/state-badge";
import { Surface } from "../../../components/ui/surface";
import { assessmentFailureMessage } from "../../assessments/api/assessment-foundation";
import type {
  Notification,
  NotificationPage,
} from "../../assessments/api/publication-contract.generated";
import { formatUtc } from "../../assessments/utils/utc-time";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import type { NotificationApiClient } from "../api/notification-api-client";
import { useNotificationClient } from "../hooks/use-notification-client";
import {
  confirmedCutoff,
  cutoffRemainingGuidance,
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
  return `/app/assessments/${notification.publicationId}/attempts/${notification.attemptId}`;
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
  const client = useNotificationClient();
  const scope = `${user?.id}:${getSessionGeneration()}`;
  return (
    <ApplicationShell currentSection="notifications">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <Link className={OUTLINE_LINK_CLASSES} href="/app">
          Back to application home
        </Link>
        <p className="text-sm font-semibold text-primary">
          Learning · Assessment notices
        </p>
      </div>
      {!user?.roles.includes("STUDENT") ? (
        <Alert title="Student access required" variant="warning">
          The assessment inbox belongs to the authenticated recipient with
          current Student authority.{" "}
          <Link className="font-semibold underline" href="/app">
            Return to application
          </Link>
          .
        </Alert>
      ) : client === null ? (
        <Alert title="Inbox unavailable" variant="warning">
          Your authenticated request service is unavailable. Sign in again
          before continuing.
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
    <div className="mx-auto max-w-4xl space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-sm font-semibold text-primary">
            Assessment notifications
          </p>
          <h1 className="mt-1 font-heading text-3xl font-bold">Your inbox</h1>
          <p className="mt-2 max-w-2xl text-sm text-foreground-secondary">
            Durable closure notices addressed to your account. Reading a notice
            is not an acknowledgment and never starts or changes a 48-hour
            period; only the server&apos;s confirmed accessibility of your
            notice does.
          </p>
        </div>
        <Button
          disabled={loadingMore}
          isLoading={loading}
          loadingLabel="Refreshing inbox"
          onClick={() => {
            setLoading(true);
            void load(undefined);
          }}
          variant="secondary"
        >
          Refresh inbox
        </Button>
      </header>
      {failureText ? (
        <Alert title="Inbox could not be read" variant="warning">
          {failureText}
        </Alert>
      ) : null}
      {loading ? (
        <Surface className="p-6">
          <div className="flex items-center gap-3" role="status">
            <LoadingIndicator label="Loading notifications" />
            Loading your assessment notices…
          </div>
        </Surface>
      ) : failureText === null && items.length === 0 ? (
        <Surface className="p-6">
          <h2 className="text-xl font-bold">No assessment notices yet</h2>
          <p className="mt-2 text-sm text-foreground-secondary">
            When a Teacher requests delayed closure of an assessment you have an
            active attempt in, a durable notice appears here.
          </p>
        </Surface>
      ) : (
        <ul aria-label="Assessment notifications" className="space-y-4">
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
                      {noticeStatusLabels[item.noticeStatus]}
                    </StateBadge>
                  </div>
                  <dl className="grid gap-3 text-sm sm:grid-cols-2">
                    <div>
                      <dt className="text-xs font-medium text-foreground-muted">
                        Published
                      </dt>
                      <dd className="mt-0.5">{formatUtc(item.publishedAt)}</dd>
                    </div>
                    <div>
                      <dt className="text-xs font-medium text-foreground-muted">
                        {cutoff === null
                          ? "48-hour period"
                          : "Confirmed cutoff (authoritative)"}
                      </dt>
                      <dd className="mt-0.5">
                        {cutoff === null
                          ? missingCutoffLabel(item)
                          : formatUtc(cutoff.closureCutoffAt)}
                      </dd>
                    </div>
                  </dl>
                  {cutoff !== null &&
                  item.noticeStatus === "NOTICE_CONFIRMED" ? (
                    <p className="text-sm text-foreground-secondary">
                      {cutoffRemainingGuidance(
                        item.serverNow,
                        cutoff.closureCutoffAt,
                      ) ?? "Cutoff confirmed; refresh for current guidance."}
                    </p>
                  ) : null}
                  <Link
                    className={OUTLINE_LINK_CLASSES}
                    href={`/app/notifications/${item.notificationId}`}
                  >
                    View notice
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
            loadingLabel="Loading more notices"
            onClick={() => void loadMore(nextCursor)}
            variant="secondary"
          >
            Load more notices
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
  const client = useNotificationClient();
  const scope = `${user?.id}:${getSessionGeneration()}:${notificationId}`;
  return (
    <ApplicationShell currentSection="notifications">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <Link className={OUTLINE_LINK_CLASSES} href="/app/notifications">
          Back to inbox
        </Link>
        <p className="text-sm font-semibold text-primary">
          Learning · Assessment notice
        </p>
      </div>
      {!user?.roles.includes("STUDENT") ? (
        <Alert title="Student access required" variant="warning">
          This notice belongs to its recipient with current Student authority.{" "}
          <Link className="font-semibold underline" href="/app">
            Return to application
          </Link>
          .
        </Alert>
      ) : client === null ? (
        <Alert title="Notice unavailable" variant="warning">
          Your authenticated request service is unavailable. Sign in again
          before continuing.
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

function localDisplay(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return "";
  return parsed.toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
}

export function NotificationDetailScreen({
  client,
  notificationId,
}: {
  readonly client: NotificationApiClient;
  readonly notificationId: string;
}) {
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
      <Surface className="mx-auto max-w-3xl p-6">
        <h1 className="font-heading text-3xl font-bold">Assessment notice</h1>
        {loading ? (
          <div className="mt-5 flex items-center gap-3" role="status">
            <LoadingIndicator label="Loading notice" />
            Loading your notice…
          </div>
        ) : (
          <>
            <Alert
              className="mt-5"
              title="Notice could not be loaded"
              variant="warning"
            >
              {failureText ?? "This notification is unavailable."}
            </Alert>
            <Button
              className="mt-4"
              onClick={() => {
                setLoading(true);
                void refresh();
              }}
              variant="secondary"
            >
              Retry notice
            </Button>
          </>
        )}
      </Surface>
    );

  const cutoff = confirmedCutoff(notice);
  return (
    <div className="mx-auto max-w-3xl space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-primary">
            Assessment notice
          </p>
          <h1 className="mt-1 break-words font-heading text-3xl font-bold">
            {notice.publicationTitle}
          </h1>
          <div className="mt-3">
            <StateBadge variant={noticeStatusVariants[notice.noticeStatus]}>
              {noticeStatusLabels[notice.noticeStatus]}
            </StateBadge>
          </div>
        </div>
        <Button
          isLoading={loading}
          loadingLabel="Refreshing notice"
          onClick={() => {
            setLoading(true);
            void refresh();
          }}
          variant="secondary"
        >
          Refresh notice
        </Button>
      </header>
      {failureText ? (
        <Alert title="Notice could not be refreshed" variant="warning">
          {failureText} Previously verified content remains visible below.
        </Alert>
      ) : null}
      <Surface className="space-y-4 p-5 sm:p-6">
        <h2 className="font-heading text-xl font-bold">Notice message</h2>
        <p className="break-words whitespace-pre-line text-sm leading-7 text-foreground-secondary">
          {notice.message}
        </p>
      </Surface>
      <Surface className="p-5 sm:p-6">
        <h2 className="mb-5 font-heading text-xl font-bold">
          Authoritative timeline
        </h2>
        <dl className="grid gap-4 sm:grid-cols-2">
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              Published
            </dt>
            <dd className="mt-1 text-sm">
              {formatUtc(notice.publishedAt)}
              <br />
              <span className="text-foreground-muted">
                {localDisplay(notice.publishedAt)} (your local time)
              </span>
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              Notice confirmed
            </dt>
            <dd className="mt-1 text-sm">
              {notice.confirmedNoticeAt === null ? (
                missingCutoffLabel(notice)
              ) : (
                <>
                  {formatUtc(notice.confirmedNoticeAt)}
                  <br />
                  <span className="text-foreground-muted">
                    {localDisplay(notice.confirmedNoticeAt)} (your local time)
                  </span>
                </>
              )}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              48-hour cutoff
            </dt>
            <dd className="mt-1 text-sm">
              {notice.closureCutoffAt === null ? (
                missingCutoffLabel(notice)
              ) : (
                <>
                  {formatUtc(notice.closureCutoffAt)}
                  <br />
                  <span className="text-foreground-muted">
                    {localDisplay(notice.closureCutoffAt)} (your local time)
                  </span>
                </>
              )}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              Server time of this response
            </dt>
            <dd className="mt-1 text-sm">{formatUtc(notice.serverNow)}</dd>
          </div>
        </dl>
        {cutoff !== null && notice.noticeStatus === "NOTICE_CONFIRMED" ? (
          <p className="mt-4 text-sm font-medium text-foreground-secondary">
            {cutoffRemainingGuidance(
              notice.serverNow,
              cutoff.closureCutoffAt,
            ) ?? "Cutoff confirmed; refresh for current guidance."}{" "}
            The server timestamps above remain authoritative.
          </p>
        ) : null}
        {notice.noticeStatus === "PREPARING" ? (
          <Alert
            className="mt-4"
            title="Your notice is being prepared"
            variant="info"
          >
            The 48-hour period starts only when the server confirms this notice
            is genuinely accessible for you. Until then there is no cutoff and
            no countdown; nothing is asked of you yet.
          </Alert>
        ) : null}
        {notice.noticeStatus === "CANCELLED" ? (
          <Alert className="mt-4" title="Closure cancelled" variant="info">
            The Teacher cancelled this closure request. This notice is retained
            as history and is no longer actionable.
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
                ? "Cutoff reached; finalization pending"
                : "Outcome recorded"
            }
            variant={
              notice.noticeStatus === "SUBMITTED" ||
              notice.noticeStatus === "FINALIZED"
                ? "success"
                : "info"
            }
          >
            {notice.noticeStatus === "SUBMITTED"
              ? "You submitted this attempt yourself."
              : notice.noticeStatus === "ABANDONED"
                ? "You abandoned this attempt; it has no grade."
                : notice.noticeStatus === "CUTOFF_REACHED"
                  ? "The confirmed cutoff was reached. Refresh to track server finalization of eligible saved answers."
                  : notice.noticeStatus === "FINALIZED"
                    ? "Your saved answers were submitted and graded by the system."
                    : "This workflow was resolved without a cutoff finalization."}
            {notice.resultId !== null
              ? " A result exists for this attempt; use the Student results experience to view it."
              : ""}
          </Alert>
        ) : null}
      </Surface>
      <Surface className="p-5 sm:p-6">
        <h2 className="mb-4 font-heading text-xl font-bold">References</h2>
        <dl className="grid gap-4 sm:grid-cols-2">
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              Your attempt (authorized)
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {notice.attemptId}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              Closure request
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {notice.closureRequestId}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              Publication
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {notice.publicationId}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              Result (when finalized)
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {notice.resultId ?? "Not available yet"}
            </dd>
          </div>
        </dl>
        <p className="mt-4 text-sm leading-6 text-foreground-muted">
          Opening your attempt or result always revalidates your current access
          on the server. This notice remains readable as your own history even
          if you leave the class or the assessment closes.
        </p>
      </Surface>
    </div>
  );
}
