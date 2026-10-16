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
import type { TeacherResultRow } from "../../assessments/api/publication-contract.generated";
import { formatUtc } from "../../assessments/utils/utc-time";
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
  const client = useTeacherResultsClient();
  const scope = `${user?.id}:${getSessionGeneration()}:${publicationId}`;
  return (
    <ApplicationShell currentSection="assessments">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <Link
          className={OUTLINE_LINK_CLASSES}
          href={`/app/assessments/${publicationId}`}
        >
          Back to Publication details
        </Link>
        <p className="text-sm font-semibold text-primary">Teaching · Results</p>
      </div>
      {!user?.roles.includes("TEACHER") ? (
        <Alert title="Teacher access required" variant="warning">
          Current Teacher authority and Publication ownership are required.{" "}
          <Link className="font-semibold underline" href="/app">
            Return to application
          </Link>
          .
        </Alert>
      ) : activeWorkspace !== "TEACHING" ? (
        <Surface className="p-6">
          <h1 className="text-2xl font-bold">Open the Teaching workspace</h1>
          <p className="mt-3 text-foreground-secondary">
            Results are available to the owning Teacher; the server checks
            ownership for every read.
          </p>
          <Button className="mt-5" onClick={() => switchWorkspace("TEACHING")}>
            Switch to Teaching workspace
          </Button>
        </Surface>
      ) : client === null ? (
        <Alert title="Results unavailable" variant="warning">
          Your authenticated request service is unavailable. Sign in again
          before continuing.
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
    <div className="mx-auto max-w-5xl space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-primary">
            Publication results
          </p>
          <h1 className="mt-1 font-heading text-3xl font-bold">
            Attempt results
          </h1>
          <p className="mt-2 max-w-2xl text-sm text-foreground-secondary">
            Owner-authorized Attempt rows with exact totals. ACTIVE and
            ABANDONED attempts have no result yet. No answers or answer keys are
            included in this data.
          </p>
        </div>
        <Button
          disabled={loadingMore}
          isLoading={loading}
          loadingLabel="Refreshing results"
          onClick={() => {
            setLoading(true);
            void load();
          }}
          variant="secondary"
        >
          Refresh results
        </Button>
      </header>
      <p className="break-all font-mono text-xs text-foreground-muted">
        Publication ID: {publicationId}
      </p>
      {failureText ? (
        <Alert title="Results could not be read" variant="warning">
          {failureText}
        </Alert>
      ) : null}
      {loading ? (
        <Surface className="p-6">
          <div className="flex items-center gap-3" role="status">
            <LoadingIndicator label="Loading results" />
            Loading authoritative Attempt rows…
          </div>
        </Surface>
      ) : failureText === null && rows.length === 0 ? (
        <Surface className="p-6">
          <h2 className="text-xl font-bold">No attempt rows yet</h2>
          <p className="mt-2 text-sm text-foreground-secondary">
            No attempts exist for this Publication, or none are visible to your
            account.
          </p>
        </Surface>
      ) : (
        <Surface
          aria-label="Scrollable Publication attempt results"
          className="overflow-x-auto p-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2"
          role="region"
          tabIndex={0}
        >
          <table className="w-full min-w-[720px] text-left text-sm">
            <caption className="sr-only">
              Attempt rows with state and exact totals for this Publication
            </caption>
            <thead>
              <tr className="border-b border-border text-xs uppercase tracking-wide text-foreground-muted">
                <th className="px-4 py-3 font-semibold" scope="col">
                  Ordinal
                </th>
                <th className="px-4 py-3 font-semibold" scope="col">
                  Attempt ID
                </th>
                <th className="px-4 py-3 font-semibold" scope="col">
                  Student ID
                </th>
                <th className="px-4 py-3 font-semibold" scope="col">
                  State
                </th>
                <th className="px-4 py-3 font-semibold" scope="col">
                  Score
                </th>
                <th className="px-4 py-3 font-semibold" scope="col">
                  Finalized
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
                      aria-label={`Open monitoring for Attempt ${row.attemptId}`}
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
                      {attemptStateLabels[row.state]}
                    </StateBadge>
                  </td>
                  <td className="px-4 py-3">
                    {row.result === null ? (
                      <span className="text-foreground-muted">
                        {row.state === "ACTIVE"
                          ? "No result (still active)"
                          : "No result (ungraded)"}
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
                          {terminalReasonLabels[row.result.terminalReason]}
                        </span>
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-xs tabular-nums">
                    {row.result === null
                      ? "—"
                      : formatUtc(row.result.finalizedAt)}
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
            loadingLabel="Loading more rows"
            onClick={() => void loadMore(nextCursor)}
            variant="secondary"
          >
            Load more rows
          </Button>
        </div>
      ) : null}
      <p className="text-xs leading-5 text-foreground-muted">
        Attempt IDs are the real authorized identities returned by the server.
        Each per-attempt monitoring destination performs a fresh server lookup;
        this results table does not grant monitoring access or discover a global
        roster.
      </p>
    </div>
  );
}
