"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button, OUTLINE_LINK_CLASSES } from "../../../components/ui/button";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { StateBadge } from "../../../components/ui/state-badge";
import { Surface } from "../../../components/ui/surface";
import { formatInstant } from "../../../lib/i18n/core";
import { useI18n } from "../../../lib/i18n/provider";
import { assessmentFailureMessage } from "../../assessments/api/assessment-foundation";
import type { TeacherResultRow } from "../../assessments/api/publication-contract.generated";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import type { TeacherResultsApiClient } from "../api/teacher-results-api-client";
import { useTeacherResultsClient } from "../hooks/use-teacher-results-client";
import {
  attemptStateLabels,
  attemptStateVariants,
  formatWeightTotal,
  monitoringHrefForAttempt,
  terminalReasonLabels,
} from "../model/teacher-results";

export function TeacherResultsPage({
  publicationId,
}: {
  readonly publicationId: string;
}) {
  return (
    <AuthenticatedBoundary>
      <TeacherResultsRouterPage publicationId={publicationId} />
    </AuthenticatedBoundary>
  );
}

function TeacherResultsRouterPage({
  publicationId,
}: {
  readonly publicationId: string;
}) {
  const { user, activeWorkspace, switchWorkspace, getSessionGeneration } =
    useAuth();
  const { t } = useI18n();
  const client = useTeacherResultsClient();
  const scope = `${user?.id}:${getSessionGeneration()}:${publicationId}`;
  return (
    <ApplicationShell currentSection="assessments">
      <div className="mb-6">
        <Link
          className={OUTLINE_LINK_CLASSES}
          href={`/app/assessments/${publicationId}`}
        >
          {t("Back to Publication details")}
        </Link>
      </div>
      {!user?.roles.includes("TEACHER") ? (
        <Alert title={t("Teacher access required")} variant="warning">
          {t(
            "Current Teacher authority and Publication ownership are required.",
          )}{" "}
          <Link className="rounded py-2 font-semibold underline" href="/app">
            {t("Return to application")}
          </Link>
          .
        </Alert>
      ) : activeWorkspace !== "TEACHING" ? (
        <Surface className="p-6">
          <h1 className="text-2xl font-bold">
            {t("Open the Teaching workspace")}
          </h1>
          <p className="mt-3 text-foreground-secondary">
            {t(
              "Results are available to the owning Teacher; the server checks ownership for every read.",
            )}
          </p>
          <Button className="mt-5" onClick={() => switchWorkspace("TEACHING")}>
            {t("Switch to Teaching workspace")}
          </Button>
        </Surface>
      ) : client === null ? (
        <Alert title={t("Results unavailable")} variant="warning">
          {t(
            "Your authenticated request service is unavailable. Sign in again before continuing.",
          )}
        </Alert>
      ) : (
        <TeacherResultsScreen
          client={client}
          key={scope}
          publicationId={publicationId}
        />
      )}
    </ApplicationShell>
  );
}

export function TeacherResultsScreen({
  client,
  publicationId,
}: {
  readonly client: TeacherResultsApiClient;
  readonly publicationId: string;
}) {
  const { t } = useI18n();
  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-primary">
            {t("Publication results")}
          </p>
          <h1 className="mt-1 font-heading text-3xl font-bold">
            {t("Attempt results")}
          </h1>
          <p className="mt-2 max-w-2xl text-sm text-foreground-secondary">
            {t(
              "Owner-authorized Attempt rows with exact totals. ACTIVE and ABANDONED attempts have no result yet. No answers or answer keys are included in this data.",
            )}
          </p>
        </div>
      </header>
      <p className="break-all font-mono text-xs text-foreground-muted">
        {t("Publication ID: {id}", { id: publicationId })}
      </p>
      <PublicationResultsSection
        client={client}
        publicationId={publicationId}
      />
    </div>
  );
}

/**
 * Embeddable owner-authorized results table with its own data fetching,
 * loading/empty/error states and opaque-cursor load-more. Reused by the
 * standalone /results route (inside TeacherResultsScreen) and embedded on the
 * unified OPEN/CLOSED Publication detail page.
 */
export function PublicationResultsSection({
  client,
  publicationId,
}: {
  readonly client: TeacherResultsApiClient;
  readonly publicationId: string;
}) {
  const { t, localize, locale } = useI18n();
  const lifetime = useRef<AbortController | null>(null);
  const readController = useRef<AbortController | null>(null);
  const readSequence = useRef(0);
  const pageController = useRef<AbortController | null>(null);
  const pageSequence = useRef(0);
  const pageInFlight = useRef(false);
  const [rows, setRows] = useState<readonly TeacherResultRow[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [failureText, setFailureText] = useState<string | null>(null);

  const load = useCallback((): Promise<void> => {
    const sequence = ++readSequence.current;
    readController.current?.abort();
    pageController.current?.abort();
    pageSequence.current += 1;
    pageInFlight.current = false;
    const controller = new AbortController();
    readController.current = controller;
    return client
      .results(publicationId, undefined, controller.signal)
      .then((result) => {
        if (
          controller.signal.aborted ||
          lifetime.current?.signal.aborted ||
          sequence !== readSequence.current
        )
          return;
        setLoadingMore(false);
        setLoading(false);
        if (!result.ok) {
          if (
            result.error.kind === "session" ||
            (result.error.kind === "api" &&
              [401, 403, 404].includes(result.error.status))
          ) {
            setRows([]);
            setNextCursor(null);
          }
          setFailureText(assessmentFailureMessage(result.error));
          return;
        }
        setFailureText(null);
        setRows(result.value.items);
        setNextCursor(result.value.nextCursor);
      });
  }, [client, publicationId]);

  const loadMore = useCallback(
    async (cursor: string) => {
      if (pageInFlight.current || lifetime.current?.signal.aborted) return;
      pageInFlight.current = true;
      const sequence = ++pageSequence.current;
      pageController.current?.abort();
      const controller = new AbortController();
      pageController.current = controller;
      setLoadingMore(true);
      const page = await client.results(
        publicationId,
        { cursor },
        controller.signal,
      );
      if (
        controller.signal.aborted ||
        lifetime.current?.signal.aborted ||
        sequence !== pageSequence.current
      )
        return;
      setLoadingMore(false);
      pageInFlight.current = false;
      if (!page.ok) {
        if (
          page.error.kind === "session" ||
          (page.error.kind === "api" &&
            [401, 403, 404].includes(page.error.status))
        ) {
          setRows([]);
          setNextCursor(null);
        }
        setFailureText(assessmentFailureMessage(page.error));
        return;
      }
      setFailureText(null);
      setRows((current) => {
        const seen = new Set(current.map((row) => row.attemptId));
        return [
          ...current,
          ...page.value.items.filter((row) => !seen.has(row.attemptId)),
        ];
      });
      setNextCursor(page.value.nextCursor);
    },
    [client, publicationId],
  );

  useEffect(() => {
    const scope = new AbortController();
    lifetime.current = scope;
    void load();
    return () => {
      scope.abort();
      readController.current?.abort();
      pageController.current?.abort();
    };
  }, [load]);

  return (
    <section aria-label={t("Results")} className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-heading text-xl font-bold">{t("Results")}</h2>
        <Button
          disabled={loadingMore}
          isLoading={loading}
          loadingLabel={t("Refreshing results")}
          onClick={() => {
            setLoading(true);
            void load();
          }}
          variant="secondary"
        >
          {t("Refresh results")}
        </Button>
      </div>
      {failureText ? (
        <Alert title={t("Results could not be read")} variant="warning">
          {localize(failureText)}
        </Alert>
      ) : null}
      {loading ? (
        <Surface className="p-6">
          <div className="flex items-center gap-3" role="status">
            <LoadingIndicator label={t("Loading results")} />
            {t("Loading authoritative Attempt rows…")}
          </div>
        </Surface>
      ) : failureText === null && rows.length === 0 ? (
        <Surface className="p-6">
          <h3 className="text-xl font-bold">{t("No results yet")}</h3>
          <p className="mt-2 text-sm text-foreground-secondary">
            {t(
              "No attempts exist for this Publication, or none are visible to your account.",
            )}
          </p>
        </Surface>
      ) : (
        <Surface
          aria-label={t("Scrollable Publication attempt results")}
          className="overflow-x-auto p-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2"
          role="region"
          tabIndex={0}
        >
          <table className="w-full min-w-[720px] text-left text-sm">
            <caption className="sr-only">
              {t(
                "Attempt rows with state and exact totals for this Publication",
              )}
            </caption>
            <thead>
              <tr className="border-b border-border text-xs uppercase tracking-wide text-foreground-muted">
                <th className="px-4 py-3 font-semibold" scope="col">
                  {t("Ordinal")}
                </th>
                <th className="px-4 py-3 font-semibold" scope="col">
                  {t("Attempt ID")}
                </th>
                <th className="px-4 py-3 font-semibold" scope="col">
                  {t("Student ID")}
                </th>
                <th className="px-4 py-3 font-semibold" scope="col">
                  {t("State")}
                </th>
                <th className="px-4 py-3 font-semibold" scope="col">
                  {t("Score")}
                </th>
                <th className="px-4 py-3 font-semibold" scope="col">
                  {t("Finalized")}
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr
                  className="border-b border-border/60 last:border-b-0"
                  key={row.attemptId}
                >
                  <td className="px-4 py-3 font-mono text-xs tabular-nums">
                    {row.ordinal}
                  </td>
                  <td className="px-4 py-3 font-mono text-xs">
                    <Link
                      aria-label={t("Open monitoring for Attempt {attemptId}", {
                        attemptId: row.attemptId,
                      })}
                      className="font-semibold text-primary underline decoration-primary/40 underline-offset-4"
                      href={monitoringHrefForAttempt(row.attemptId)}
                    >
                      {row.attemptId}
                    </Link>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs">
                    {row.studentId}
                  </td>
                  <td className="px-4 py-3 tabular-nums">
                    <StateBadge variant={attemptStateVariants[row.state]}>
                      {localize(attemptStateLabels[row.state])}
                    </StateBadge>
                  </td>
                  <td className="px-4 py-3">
                    {row.result === null ? (
                      <span className="text-foreground-muted">
                        {row.state === "ACTIVE"
                          ? t("No result (still active)")
                          : t("No result (ungraded)")}
                      </span>
                    ) : (
                      <span>
                        <span className="font-semibold">
                          {row.result.percentageDisplay}%
                        </span>
                        <span className="ml-2 font-mono text-xs text-foreground-secondary">
                          {formatWeightTotal(row)}
                        </span>
                        <span className="mt-0.5 block text-xs text-foreground-muted">
                          {localize(
                            terminalReasonLabels[row.result.terminalReason],
                          )}
                        </span>
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-xs tabular-nums">
                    {row.result === null
                      ? "—"
                      : formatInstant(locale, row.result.finalizedAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Surface>
      )}
      {nextCursor !== null ? (
        <div className="flex justify-center">
          <Button
            disabled={loading || loadingMore}
            isLoading={loadingMore}
            loadingLabel={t("Loading more rows")}
            onClick={() => void loadMore(nextCursor)}
            variant="secondary"
          >
            {t("Load more rows")}
          </Button>
        </div>
      ) : null}
      <p className="text-xs leading-5 text-foreground-muted">
        {t(
          "Attempt IDs are the real authorized identities returned by the server. Each per-attempt monitoring destination performs a fresh server lookup; this results table does not grant monitoring access or discover a global roster.",
        )}
      </p>
    </section>
  );
}
