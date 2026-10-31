"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { StateBadge } from "../../../components/ui/state-badge";
import { Surface } from "../../../components/ui/surface";
import { formatInstant } from "../../../lib/i18n/core";
import type { TranslationKey } from "../../../lib/i18n/core";
import { useI18n } from "../../../lib/i18n/provider";
import { assessmentFailureMessage } from "../../assessments/api/assessment-foundation";
import { canonicalUuid } from "../../assessments/model/publication";
import { useRealtimeResync } from "../../realtime/hooks/use-realtime-subscriptions";
import type { TeacherDashboardApiClient } from "../api/teacher-dashboard-api-client";
import type {
  StudentDirection,
  StudentSort,
  StudentStatusFilter,
  TeacherScoreBin,
  TeacherStudentOverview,
  TeacherStudentSummary,
} from "../model/teacher-dashboard";
import {
  attemptStateLabels,
  attemptStateVariants,
  monitoringHrefForAttempt,
  terminalReasonLabels,
} from "../model/teacher-results";

const DIRECTORY_PAGE_LIMIT = 20;
const HISTORY_PAGE_LIMIT = 20;

const statusFilterLabels: Record<StudentStatusFilter, TranslationKey> = {
  ACTIVE: "Active",
  SUBMITTED: "Submitted",
  ABANDONED: "Abandoned",
};

const sortLabels: Record<StudentSort, TranslationKey> = {
  LATEST: "Last activity",
  BEST_SCORE: "Best score",
};

function initialsFor(name: string | null, studentId: string): string {
  if (name !== null && name.trim().length > 0) {
    const parts = name.trim().split(/\s+/).slice(0, 2);
    return parts.map((part) => part.charAt(0).toUpperCase()).join("");
  }
  return studentId.slice(0, 2).toUpperCase();
}

function formatCount(count: string): string {
  // Exact canonical decimal string rendered verbatim; never routed through
  // Number so 64-bit counts cannot lose precision.
  return count;
}

function binRangeLabel(bin: TeacherScoreBin): string {
  return bin.index === 0
    ? `0 – ${bin.upperBound}`
    : `${bin.lowerBound} – ${bin.upperBound}`;
}

/**
 * Server-authoritative 20-bin histogram (one best graded SUBMITTED score per
 * student, upper-inclusive half-point bins on 0–10). Bars visualize the exact
 * server counts; the always-available data table is the accessible
 * representation, so the bar region itself is hidden from assistive
 * technology instead of duplicating ambiguous geometry.
 */
function ScoreDistributionChart({
  bins,
}: {
  readonly bins: readonly TeacherScoreBin[];
}) {
  const { t } = useI18n();
  const max = bins.reduce(
    (highest, bin) =>
      BigInt(bin.count) > highest ? BigInt(bin.count) : highest,
    BigInt(0),
  );
  return (
    <Surface className="p-5">
      <h3 className="font-heading text-lg font-bold">
        {t("Score distribution")}
      </h3>
      <p className="mt-1 text-sm text-foreground-secondary">
        {t(
          "Each graded student appears once with their best submitted score, in half-point ranges from 0 to 10.",
        )}
      </p>
      <div
        aria-hidden="true"
        className="mt-5 flex h-40 items-end gap-1 border-b border-border-strong"
      >
        {bins.map((bin) => {
          const count = BigInt(bin.count);
          const height =
            max === BigInt(0) || count === BigInt(0)
              ? 0
              : Math.max(4, Number((count * BigInt(100)) / max));
          return (
            <div
              className="flex h-full flex-1 flex-col items-center justify-end gap-1"
              key={bin.index}
              title={`${binRangeLabel(bin)}: ${bin.count}`}
            >
              {count > BigInt(0) ? (
                <span className="text-[0.6rem] leading-none text-foreground-secondary tabular-nums">
                  {bin.count}
                </span>
              ) : null}
              <div
                className="w-full min-w-2 rounded-t-sm bg-gradient-to-t from-primary/70 to-secondary/70"
                style={{ height: `${height}%` }}
              />
            </div>
          );
        })}
      </div>
      <div aria-hidden="true" className="flex gap-1">
        {bins.map((bin) => (
          <span
            className="flex-1 truncate text-center text-[0.55rem] leading-4 text-foreground-muted tabular-nums"
            key={bin.index}
          >
            {bin.lowerBound}
          </span>
        ))}
        <span className="text-[0.55rem] leading-4 text-foreground-muted tabular-nums">
          10
        </span>
      </div>
      <details className="mt-4" open>
        <summary className="min-h-11 cursor-pointer list-none rounded-lg px-2 py-2 text-sm font-semibold text-primary hover:underline hover:decoration-primary/40 hover:underline-offset-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus [&::-webkit-details-marker]:hidden">
          {t("Score table")}
        </summary>
        <Surface
          className="mt-3 p-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2"
          role="region"
          aria-label={t("Score distribution table")}
          tabIndex={0}
        >
          <table className="w-full text-left text-sm">
            <caption className="sr-only">
              {t(
                "Students per half-point score range, best submitted score per student",
              )}
            </caption>
            <thead>
              <tr className="border-b border-border text-xs uppercase tracking-wide text-foreground-muted">
                <th className="px-4 py-2 font-semibold" scope="col">
                  {t("Score range")}
                </th>
                <th className="px-4 py-2 font-semibold" scope="col">
                  {t("Students")}
                </th>
              </tr>
            </thead>
            <tbody>
              {bins.map((bin) => (
                <tr
                  className="border-b border-border/60 last:border-b-0"
                  key={bin.index}
                >
                  <td className="px-4 py-1.5 tabular-nums">
                    {binRangeLabel(bin)}
                  </td>
                  <td className="px-4 py-1.5 tabular-nums">{bin.count}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Surface>
      </details>
    </Surface>
  );
}

function OverviewCard({
  label,
  value,
  hint,
}: {
  readonly label: string;
  readonly value: string;
  readonly hint?: string;
}) {
  return (
    <Surface className="min-w-0 p-4">
      <p className="text-xs font-semibold uppercase tracking-wide text-foreground-muted">
        {label}
      </p>
      <p className="mt-2 font-heading text-2xl font-bold tabular-nums">
        {value}
      </p>
      {hint ? (
        <p className="mt-1 text-xs leading-4 text-foreground-secondary">
          {hint}
        </p>
      ) : null}
    </Surface>
  );
}

function DirectoryControls({
  status,
  sort,
  direction,
  onStatusChange,
  onSortChange,
  search,
  onSearchTextChange,
  onSearchSubmit,
  disabled,
}: {
  readonly status: StudentStatusFilter | null;
  readonly sort: StudentSort;
  readonly direction: StudentDirection;
  readonly onStatusChange: (status: StudentStatusFilter | null) => void;
  readonly onSortChange: (sort: StudentSort) => void;
  readonly search: string;
  readonly onSearchTextChange: (value: string) => void;
  readonly onSearchSubmit: (value: string) => void;
  readonly disabled: boolean;
}) {
  const { t } = useI18n();
  return (
    <div className="flex flex-wrap items-end gap-3">
      <div className="min-w-0">
        <label
          className="block text-sm font-semibold text-foreground-secondary"
          htmlFor="student-status-filter"
        >
          {t("Attempt state")}
        </label>
        <select
          aria-label={t("Filter students by attempt state")}
          className="mt-1 min-h-11 rounded-lg border border-border bg-white px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus"
          disabled={disabled}
          id="student-status-filter"
          onChange={(event) =>
            onStatusChange(
              event.target.value === "ALL"
                ? null
                : (event.target.value as StudentStatusFilter),
            )
          }
          value={status ?? "ALL"}
        >
          <option value="ALL">{t("All states")}</option>
          {(Object.keys(statusFilterLabels) as StudentStatusFilter[]).map(
            (value) => (
              <option key={value} value={value}>
                {t(statusFilterLabels[value])}
              </option>
            ),
          )}
        </select>
      </div>
      <div className="min-w-0">
        <label
          className="block text-sm font-semibold text-foreground-secondary"
          htmlFor="student-sort"
        >
          {t("Sort by")}
        </label>
        <select
          aria-label={t("Sort the student list")}
          className="mt-1 min-h-11 rounded-lg border border-border bg-white px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus"
          disabled={disabled}
          id="student-sort"
          onChange={(event) => onSortChange(event.target.value as StudentSort)}
          value={sort}
        >
          {(Object.keys(sortLabels) as StudentSort[]).map((value) => (
            <option key={value} value={value}>
              {t(sortLabels[value])}
            </option>
          ))}
        </select>
      </div>
      <Button
        aria-label={t("Reverse sort order")}
        disabled={disabled}
        onClick={() => onSortChange(sort)}
        variant="secondary"
      >
        {direction === "DESC" ? t("Highest first") : t("Lowest first")}
      </Button>
      <form
        className="flex min-w-0 flex-1 items-end gap-2 sm:max-w-sm"
        onSubmit={(event) => {
          event.preventDefault();
          onSearchSubmit(search);
        }}
      >
        <div className="min-w-0 flex-1">
          <label
            className="block text-sm font-semibold text-foreground-secondary"
            htmlFor="student-id-search"
          >
            {t("Find student")}
          </label>
          <input
            aria-label={t("Find a student by their exact student ID")}
            className="mt-1 w-full rounded-lg border border-border bg-white px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus"
            disabled={disabled}
            id="student-id-search"
            onChange={(event) => onSearchTextChange(event.target.value)}
            placeholder={t("Student ID")}
            type="text"
            value={search}
          />
        </div>
        <Button disabled={disabled} type="submit" variant="secondary">
          {t("Find")}
        </Button>
      </form>
    </div>
  );
}

/**
 * The one unified, student-centric results dashboard. The Student is the
 * primary row; attempts are subordinate details. All aggregates come from the
 * owner-authorized server endpoints — the browser never recalculates counters,
 * averages or bins, and ungraded attempts never render as scores.
 */
export function StudentResultsDashboardSection({
  client,
  publicationId,
  onRegisterInvalidate,
}: {
  readonly client: TeacherDashboardApiClient;
  readonly publicationId: string;
  /**
   * The embedding page owns the single realtime subscription for the
   * Publication and forwards invalidation hints into the dashboard's debounced
   * authoritative re-fetch (REST stays authoritative; WS is a hint only).
   */
  readonly onRegisterInvalidate?: (invalidate: () => void) => void;
}) {
  const { t, localize, locale } = useI18n();
  const lifetime = useRef<AbortController | null>(null);

  const overviewController = useRef<AbortController | null>(null);
  const overviewSequence = useRef(0);
  const directoryController = useRef<AbortController | null>(null);
  const directorySequence = useRef(0);
  const directoryPageController = useRef<AbortController | null>(null);
  const directoryPageSequence = useRef(0);
  const directoryPageInFlight = useRef(false);
  const historyController = useRef<AbortController | null>(null);
  const historySequence = useRef(0);
  const historyPageController = useRef<AbortController | null>(null);
  const historyPageSequence = useRef(0);
  const historyPageInFlight = useRef(false);

  const [overview, setOverview] = useState<TeacherStudentOverview | null>(null);
  const [overviewLoading, setOverviewLoading] = useState(true);
  const [overviewFailure, setOverviewFailure] = useState<string | null>(null);

  const [rows, setRows] = useState<readonly TeacherStudentSummary[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [directoryLoading, setDirectoryLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [directoryFailure, setDirectoryFailure] = useState<string | null>(null);

  const [statusFilter, setStatusFilter] = useState<StudentStatusFilter | null>(
    null,
  );
  const [sort, setSort] = useState<StudentSort>("LATEST");
  const [direction, setDirection] = useState<StudentDirection>("DESC");
  const [studentIdSearch, setStudentIdSearch] = useState("");
  const [appliedStudentId, setAppliedStudentId] = useState<string | null>(null);
  const [searchInvalid, setSearchInvalid] = useState(false);

  const [selectedStudent, setSelectedStudent] =
    useState<TeacherStudentSummary | null>(null);
  const [historyRows, setHistoryRows] = useState<
    readonly TeacherStudentSummary["latestAttempt"][]
  >([]);
  const [historyNextCursor, setHistoryNextCursor] = useState<string | null>(
    null,
  );
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyLoadingMore, setHistoryLoadingMore] = useState(false);
  const [historyFailure, setHistoryFailure] = useState<string | null>(null);

  const applySearch = useCallback((raw: string) => {
    const trimmed = raw.trim();
    if (trimmed === "") {
      setSearchInvalid(false);
      setAppliedStudentId(null);
      return;
    }
    if (!canonicalUuid.safeParse(trimmed).success) {
      setSearchInvalid(true);
      return;
    }
    setSearchInvalid(false);
    setAppliedStudentId(trimmed);
  }, []);

  const loadOverview = useCallback((): Promise<void> => {
    const sequence = ++overviewSequence.current;
    overviewController.current?.abort();
    const controller = new AbortController();
    overviewController.current = controller;
    return client
      .studentOverview(publicationId, controller.signal)
      .then((result) => {
        if (
          controller.signal.aborted ||
          lifetime.current?.signal.aborted ||
          sequence !== overviewSequence.current
        )
          return;
        setOverviewLoading(false);
        if (!result.ok) {
          setOverview(null);
          setOverviewFailure(assessmentFailureMessage(result.error));
          return;
        }
        setOverviewFailure(null);
        setOverview(result.value);
      });
  }, [client, publicationId]);

  const loadDirectory = useCallback((): Promise<void> => {
    const sequence = ++directorySequence.current;
    directoryController.current?.abort();
    directoryPageController.current?.abort();
    directoryPageSequence.current += 1;
    directoryPageInFlight.current = false;
    const controller = new AbortController();
    directoryController.current = controller;
    return client
      .students(
        publicationId,
        {
          limit: DIRECTORY_PAGE_LIMIT,
          studentId: appliedStudentId ?? undefined,
          status: statusFilter ?? undefined,
          sort,
          direction,
        },
        controller.signal,
      )
      .then((result) => {
        if (
          controller.signal.aborted ||
          lifetime.current?.signal.aborted ||
          sequence !== directorySequence.current
        )
          return;
        setLoadingMore(false);
        setDirectoryLoading(false);
        if (!result.ok) {
          if (
            result.error.kind === "session" ||
            (result.error.kind === "api" &&
              [401, 403, 404].includes(result.error.status))
          ) {
            setRows([]);
            setNextCursor(null);
          }
          setDirectoryFailure(assessmentFailureMessage(result.error));
          return;
        }
        setDirectoryFailure(null);
        setRows(result.value.items);
        setNextCursor(result.value.nextCursor);
      });
  }, [client, publicationId, appliedStudentId, statusFilter, sort, direction]);

  const loadMore = useCallback(
    async (cursor: string) => {
      if (directoryPageInFlight.current || lifetime.current?.signal.aborted)
        return;
      directoryPageInFlight.current = true;
      const sequence = ++directoryPageSequence.current;
      directoryPageController.current?.abort();
      const controller = new AbortController();
      directoryPageController.current = controller;
      setLoadingMore(true);
      const page = await client.students(
        publicationId,
        {
          limit: DIRECTORY_PAGE_LIMIT,
          cursor,
          studentId: appliedStudentId ?? undefined,
          status: statusFilter ?? undefined,
          sort,
          direction,
        },
        controller.signal,
      );
      if (
        controller.signal.aborted ||
        lifetime.current?.signal.aborted ||
        sequence !== directoryPageSequence.current
      )
        return;
      setLoadingMore(false);
      directoryPageInFlight.current = false;
      if (!page.ok) {
        setDirectoryFailure(assessmentFailureMessage(page.error));
        return;
      }
      setDirectoryFailure(null);
      // Live keyset pages can move rows across page boundaries; deduplicate
      // by the authoritative studentId instead of trusting page membership.
      setRows((current) => {
        const seen = new Set(current.map((row) => row.studentId));
        return [
          ...current,
          ...page.value.items.filter((row) => !seen.has(row.studentId)),
        ];
      });
      setNextCursor(page.value.nextCursor);
    },
    [client, publicationId, appliedStudentId, statusFilter, sort, direction],
  );

  const loadHistory = useCallback(
    (student: TeacherStudentSummary): Promise<void> => {
      const sequence = ++historySequence.current;
      historyController.current?.abort();
      historyPageController.current?.abort();
      historyPageSequence.current += 1;
      historyPageInFlight.current = false;
      const controller = new AbortController();
      historyController.current = controller;
      setSelectedStudent(student);
      setHistoryLoading(true);
      return client
        .studentHistory(
          publicationId,
          student.studentId,
          {
            limit: HISTORY_PAGE_LIMIT,
          },
          controller.signal,
        )
        .then((result) => {
          if (
            controller.signal.aborted ||
            lifetime.current?.signal.aborted ||
            sequence !== historySequence.current
          )
            return;
          setHistoryLoadingMore(false);
          setHistoryLoading(false);
          if (!result.ok) {
            setHistoryRows([]);
            setHistoryNextCursor(null);
            setHistoryFailure(assessmentFailureMessage(result.error));
            return;
          }
          setHistoryFailure(null);
          setHistoryRows(result.value.items);
          setHistoryNextCursor(result.value.nextCursor);
        });
    },
    [client, publicationId],
  );

  const loadMoreHistory = useCallback(
    async (studentId: string, cursor: string) => {
      if (historyPageInFlight.current || lifetime.current?.signal.aborted)
        return;
      historyPageInFlight.current = true;
      const sequence = ++historyPageSequence.current;
      historyPageController.current?.abort();
      const controller = new AbortController();
      historyPageController.current = controller;
      setHistoryLoadingMore(true);
      const page = await client.studentHistory(
        publicationId,
        studentId,
        { limit: HISTORY_PAGE_LIMIT, cursor },
        controller.signal,
      );
      if (
        controller.signal.aborted ||
        lifetime.current?.signal.aborted ||
        sequence !== historyPageSequence.current
      )
        return;
      setHistoryLoadingMore(false);
      historyPageInFlight.current = false;
      if (!page.ok) {
        setHistoryFailure(assessmentFailureMessage(page.error));
        return;
      }
      setHistoryFailure(null);
      setHistoryRows((current) => {
        const seen = new Set(current.map((attempt) => attempt.attemptId));
        return [
          ...current,
          ...page.value.items.filter((attempt) => !seen.has(attempt.attemptId)),
        ];
      });
      setHistoryNextCursor(page.value.nextCursor);
    },
    [client, publicationId],
  );

  useEffect(() => {
    const scope = new AbortController();
    lifetime.current = scope;
    void loadOverview();
    return () => {
      scope.abort();
      overviewController.current?.abort();
    };
  }, [loadOverview]);

  // Filter/sort/search changes intentionally restart the first directory page:
  // the server owns sorting and filtering, so the browser never reorders rows.
  useEffect(() => {
    if (lifetime.current === null) {
      const scope = new AbortController();
      lifetime.current = scope;
    }
    void loadDirectory();
    return () => {
      directoryController.current?.abort();
      directoryPageController.current?.abort();
    };
  }, [loadDirectory]);

  const refreshAll = useCallback((): Promise<unknown> => {
    // Explicit refresh (button or realtime hint) signals a full reload; the
    // loaders themselves only clear loading asynchronously so effects never
    // call setState synchronously.
    setOverviewLoading(true);
    setDirectoryLoading(true);
    const history =
      selectedStudent === null
        ? Promise.resolve()
        : loadHistory(selectedStudent);
    return Promise.all([void loadOverview(), void loadDirectory(), history]);
  }, [loadOverview, loadDirectory, loadHistory, selectedStudent]);

  // Realtime hints and the bounded periodic resync both funnel into the same
  // coalesced, sequence-fenced REST refresh; the socket never mutates state.
  const resync = useRealtimeResync({ active: true, run: refreshAll });
  useEffect(() => {
    onRegisterInvalidate?.(resync.request);
  }, [onRegisterInvalidate, resync.request]);

  const refresh = useCallback(() => {
    void refreshAll();
  }, [refreshAll]);

  const hasGradedStudents =
    overview !== null && overview.gradedStudentCount !== "0";
  const directoryEmpty = !directoryLoading && rows.length === 0;

  return (
    <section aria-label={t("Student results dashboard")} className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h2 className="font-heading text-xl font-bold">
            {t("Student results")}
          </h2>
          <p className="mt-1 text-sm text-foreground-secondary">
            {t(
              "One row per student. Attempts, best and average scores come from the graded work of each student in this assessment.",
            )}
          </p>
        </div>
        <Button
          disabled={loadingMore || historyLoadingMore}
          isLoading={overviewLoading || directoryLoading}
          loadingLabel={t("Refreshing results")}
          onClick={refresh}
          variant="secondary"
        >
          {t("Refresh results")}
        </Button>
      </div>

      {overviewFailure !== null ? (
        <Alert title={t("Summary could not be read")} variant="warning">
          {localize(overviewFailure)}{" "}
          <Button
            className="mt-3"
            disabled={overviewLoading}
            onClick={() => {
              setOverviewLoading(true);
              void loadOverview();
            }}
            variant="secondary"
          >
            {t("Retry summary")}
          </Button>
        </Alert>
      ) : null}

      {overviewLoading && overview === null ? (
        <Surface className="p-6">
          <div className="flex items-center gap-3" role="status">
            <LoadingIndicator label={t("Loading summary")} />
            {t("Loading summary…")}
          </div>
        </Surface>
      ) : overview !== null ? (
        <>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
            <OverviewCard
              hint={t("At least one attempt is still active.")}
              label={t("Active students")}
              value={formatCount(overview.activeStudentCount)}
            />
            <OverviewCard
              hint={t("At least one submitted attempt.")}
              label={t("Submitted students")}
              value={formatCount(overview.submittedStudentCount)}
            />
            <OverviewCard
              hint={t(
                "Distinct students with any attempt, including abandoned.",
              )}
              label={t("Total participants")}
              value={formatCount(overview.participatedStudentCount)}
            />
            <OverviewCard
              hint={t("At least one completely graded attempt.")}
              label={t("Graded students")}
              value={formatCount(overview.gradedStudentCount)}
            />
            <OverviewCard
              hint={
                hasGradedStudents
                  ? t("Best submitted score out of 10.")
                  : t("No graded work yet.")
              }
              label={t("Best score")}
              value={
                overview.bestScore === null
                  ? t("No graded work yet.")
                  : `${overview.bestScore.scoreDisplay} / 10`
              }
            />
            <OverviewCard
              hint={
                overview.averageBestScore === null
                  ? t("No graded work yet.")
                  : t("Mean of one best score per graded student.", {
                      count: overview.gradedStudentCount,
                    })
              }
              label={t("Average best score")}
              value={
                overview.averageBestScore === null
                  ? t("No graded work yet.")
                  : `${overview.averageBestScore.scoreDisplay} / 10`
              }
            />
          </div>
          <p className="text-xs leading-5 text-foreground-muted">
            {t(
              "A student who submitted and started a retake appears in both groups. Groups are never added together.",
            )}
          </p>
          <ScoreDistributionChart bins={overview.scoreBins} />
          {!hasGradedStudents ? (
            <p className="text-sm text-foreground-secondary">
              {t(
                "No graded work yet, so the distribution is empty. Scores appear after attempts are submitted and graded.",
              )}
            </p>
          ) : null}
        </>
      ) : null}

      {directoryFailure !== null ? (
        <Alert title={t("Students could not be read")} variant="warning">
          {localize(directoryFailure)}{" "}
          <Button
            className="mt-3"
            disabled={directoryLoading}
            onClick={() => {
              setDirectoryLoading(true);
              void loadDirectory();
            }}
            variant="secondary"
          >
            {t("Retry students")}
          </Button>
        </Alert>
      ) : null}

      {searchInvalid ? (
        <Alert title={t("Check the student ID")} variant="warning">
          {t(
            "Enter a complete student ID (exact match) or clear the field to list everyone.",
          )}
        </Alert>
      ) : null}

      <DirectoryControls
        direction={direction}
        disabled={directoryLoading && rows.length === 0}
        onSearchSubmit={applySearch}
        onSearchTextChange={setStudentIdSearch}
        onSortChange={(next) => {
          if (next === sort)
            setDirection(direction === "DESC" ? "ASC" : "DESC");
          else {
            setSort(next);
            setDirection("DESC");
          }
        }}
        onStatusChange={(status) => setStatusFilter(status)}
        search={studentIdSearch}
        sort={sort}
        status={statusFilter}
      />

      {appliedStudentId !== null ? (
        <p className="text-sm text-foreground-secondary">
          {t("Showing the one student with this exact ID.")}{" "}
          <button
            className="rounded font-semibold text-primary underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus"
            onClick={() => {
              setStudentIdSearch("");
              setAppliedStudentId(null);
            }}
            type="button"
          >
            {t("Clear student filter")}
          </button>
        </p>
      ) : null}

      {directoryLoading && rows.length === 0 ? (
        <Surface className="p-6">
          <div className="flex items-center gap-3" role="status">
            <LoadingIndicator label={t("Loading students")} />
            {t("Loading students…")}
          </div>
        </Surface>
      ) : directoryEmpty ? (
        <Surface className="p-6">
          <h3 className="text-xl font-bold">{t("No students yet")}</h3>
          <p className="mt-2 text-sm text-foreground-secondary">
            {t(
              "No student in this assessment matches the current filters yet.",
            )}
          </p>
        </Surface>
      ) : (
        <>
          {/* Dense table from md up; mobile uses the stacked card rows below
              so no viewport-level horizontal scrolling is ever needed. */}
          <Surface
            aria-label={t("Student directory")}
            className="hidden p-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 md:block"
            role="region"
            tabIndex={0}
          >
            <table className="w-full text-left text-sm">
              <caption className="sr-only">
                {t(
                  "One row per student with current state, attempts and exact graded scores",
                )}
              </caption>
              <thead>
                <tr className="border-b border-border text-xs uppercase tracking-wide text-foreground-muted">
                  <th className="px-4 py-3 font-semibold" scope="col">
                    {t("Student")}
                  </th>
                  <th className="px-4 py-3 font-semibold" scope="col">
                    {t("Current attempt")}
                  </th>
                  <th
                    className="hidden px-4 py-3 font-semibold md:table-cell"
                    scope="col"
                  >
                    {t("Attempts")}
                  </th>
                  <th className="px-4 py-3 font-semibold" scope="col">
                    {t("Best score")}
                  </th>
                  <th
                    className="hidden px-4 py-3 font-semibold md:table-cell"
                    scope="col"
                  >
                    {t("Average")}
                  </th>
                  <th
                    className="hidden px-4 py-3 font-semibold lg:table-cell"
                    scope="col"
                  >
                    {t("Latest graded")}
                  </th>
                  <th
                    className="hidden px-4 py-3 font-semibold lg:table-cell"
                    scope="col"
                  >
                    {t("Last activity")}
                  </th>
                  <th className="px-4 py-3 font-semibold" scope="col">
                    <span className="sr-only">{t("Open attempt history")}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr
                    className="border-b border-border/60 last:border-b-0"
                    key={row.studentId}
                  >
                    <td className="px-4 py-3">
                      <span className="flex min-w-0 items-center gap-3">
                        <span
                          aria-hidden="true"
                          className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-muted text-xs font-bold text-primary"
                        >
                          {initialsFor(row.studentName, row.studentId)}
                        </span>
                        <span className="block min-w-0">
                          {row.studentName === null ? (
                            <span
                              className="break-all font-mono text-xs text-foreground-secondary"
                              title={t(
                                "Student ID shown because no name is available",
                              )}
                            >
                              {row.studentId}
                            </span>
                          ) : (
                            <>
                              <span className="block break-words font-semibold">
                                {row.studentName}
                              </span>
                              <span className="mt-0.5 block break-all font-mono text-[0.65rem] text-foreground-muted">
                                {row.studentId}
                              </span>
                            </>
                          )}
                        </span>
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <StateBadge
                        variant={attemptStateVariants[row.latestAttempt.state]}
                      >
                        {localize(attemptStateLabels[row.latestAttempt.state])}
                      </StateBadge>
                    </td>
                    <td className="hidden px-4 py-3 tabular-nums md:table-cell">
                      {formatCount(row.attemptCount)}
                      <span className="mt-0.5 block text-xs text-foreground-muted">
                        {t("{count} graded", { count: row.gradedAttemptCount })}
                      </span>
                    </td>
                    <td className="px-4 py-3 tabular-nums">
                      {row.bestResult === null ? (
                        <span className="text-foreground-muted">—</span>
                      ) : (
                        <span className="font-semibold">
                          {row.bestResult.percentageDisplay}%
                        </span>
                      )}
                    </td>
                    <td className="hidden px-4 py-3 tabular-nums md:table-cell">
                      {row.averageResult === null ? (
                        <span className="text-foreground-muted">—</span>
                      ) : (
                        `${row.averageResult.scoreDisplay} / 10`
                      )}
                    </td>
                    <td className="hidden px-4 py-3 tabular-nums lg:table-cell">
                      {row.latestGradedResult === null ? (
                        <span className="text-foreground-muted">—</span>
                      ) : (
                        `${row.latestGradedResult.percentageDisplay}%`
                      )}
                    </td>
                    <td className="hidden px-4 py-3 text-xs tabular-nums lg:table-cell">
                      {formatInstant(locale, row.latestAttempt.startedAt)}
                    </td>
                    <td className="px-4 py-3">
                      <Button
                        aria-expanded={
                          selectedStudent?.studentId === row.studentId
                        }
                        onClick={() => void loadHistory(row)}
                        variant="secondary"
                      >
                        {t("View attempts")}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Surface>
          <ul
            aria-label={t("Student directory")}
            className="space-y-3 md:hidden"
          >
            {rows.map((row) => (
              <li key={row.studentId}>
                <Surface className="space-y-3 p-4">
                  <span className="flex min-w-0 items-center gap-3">
                    <span
                      aria-hidden="true"
                      className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-muted text-xs font-bold text-primary"
                    >
                      {initialsFor(row.studentName, row.studentId)}
                    </span>
                    <span className="block min-w-0">
                      {row.studentName === null ? (
                        <span
                          className="break-all font-mono text-xs text-foreground-secondary"
                          title={t(
                            "Student ID shown because no name is available",
                          )}
                        >
                          {row.studentId}
                        </span>
                      ) : (
                        <>
                          <span className="block break-words font-semibold">
                            {row.studentName}
                          </span>
                          <span className="mt-0.5 block break-all font-mono text-[0.65rem] text-foreground-muted">
                            {row.studentId}
                          </span>
                        </>
                      )}
                    </span>
                  </span>
                  <dl className="grid grid-cols-2 gap-2 text-sm">
                    <div className="min-w-0">
                      <dt className="text-xs text-foreground-muted">
                        {t("Current attempt")}
                      </dt>
                      <dd className="mt-0.5">
                        <StateBadge
                          variant={
                            attemptStateVariants[row.latestAttempt.state]
                          }
                        >
                          {localize(
                            attemptStateLabels[row.latestAttempt.state],
                          )}
                        </StateBadge>
                      </dd>
                    </div>
                    <div className="min-w-0">
                      <dt className="text-xs text-foreground-muted">
                        {t("Attempts")}
                      </dt>
                      <dd className="mt-0.5 tabular-nums">
                        {formatCount(row.attemptCount)} ·{" "}
                        {t("{count} graded", {
                          count: row.gradedAttemptCount,
                        })}
                      </dd>
                    </div>
                    <div className="min-w-0">
                      <dt className="text-xs text-foreground-muted">
                        {t("Best score")}
                      </dt>
                      <dd className="mt-0.5 font-semibold tabular-nums">
                        {row.bestResult === null
                          ? "—"
                          : `${row.bestResult.percentageDisplay}%`}
                      </dd>
                    </div>
                    <div className="min-w-0">
                      <dt className="text-xs text-foreground-muted">
                        {t("Average")}
                      </dt>
                      <dd className="mt-0.5 tabular-nums">
                        {row.averageResult === null
                          ? "—"
                          : `${row.averageResult.scoreDisplay} / 10`}
                      </dd>
                    </div>
                  </dl>
                  <Button
                    aria-expanded={selectedStudent?.studentId === row.studentId}
                    className="w-full"
                    onClick={() => void loadHistory(row)}
                    variant="secondary"
                  >
                    {t("View attempts")}
                  </Button>
                </Surface>
              </li>
            ))}
          </ul>
        </>
      )}
      {nextCursor !== null && !directoryLoading ? (
        <div className="flex justify-center">
          <Button
            disabled={loadingMore}
            isLoading={loadingMore}
            loadingLabel={t("Loading more students")}
            onClick={() => void loadMore(nextCursor)}
            variant="secondary"
          >
            {t("Load more students")}
          </Button>
        </div>
      ) : null}

      {selectedStudent !== null ? (
        <StudentDetailPanel
          bestAttemptId={selectedStudent.bestResult?.attemptId ?? null}
          failure={historyFailure}
          loading={historyLoading}
          loadingMore={historyLoadingMore}
          onClose={() => {
            historyController.current?.abort();
            historyPageController.current?.abort();
            historySequence.current += 1;
            setSelectedStudent(null);
            setHistoryRows([]);
            setHistoryNextCursor(null);
            setHistoryFailure(null);
          }}
          onLoadMore={(cursor) =>
            void loadMoreHistory(selectedStudent.studentId, cursor)
          }
          publicationId={publicationId}
          rows={historyRows}
          nextCursor={historyNextCursor}
          student={selectedStudent}
        />
      ) : null}

      <p className="text-xs leading-5 text-foreground-muted">
        {t(
          "Answers and answer keys are never shown in this list; they appear only inside an authorized submitted-attempt review.",
        )}
      </p>
    </section>
  );
}

function StudentDetailPanel({
  bestAttemptId,
  failure,
  loading,
  loadingMore,
  nextCursor,
  onClose,
  onLoadMore,
  publicationId,
  rows,
  student,
}: {
  readonly bestAttemptId: string | null;
  readonly failure: string | null;
  readonly loading: boolean;
  readonly loadingMore: boolean;
  readonly nextCursor: string | null;
  readonly onClose: () => void;
  readonly onLoadMore: (cursor: string) => void;
  readonly publicationId: string;
  readonly rows: readonly TeacherStudentSummary["latestAttempt"][];
  readonly student: TeacherStudentSummary;
}) {
  const { t, localize, locale } = useI18n();
  const latestAttemptId = rows.length > 0 ? rows[0]?.attemptId : null;
  return (
    <Surface aria-label={t("Attempt history")} className="space-y-4 p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="font-heading text-lg font-bold">
            {student.studentName ?? t("Student attempts")}
          </h3>
          <p className="mt-1 break-all font-mono text-xs text-foreground-muted">
            {student.studentId}
          </p>
        </div>
        <Button onClick={onClose} variant="secondary">
          {t("Close student details")}
        </Button>
      </div>
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div className="min-w-0">
          <dt className="text-xs font-semibold uppercase tracking-wide text-foreground-muted">
            {t("Attempts")}
          </dt>
          <dd className="mt-1 font-semibold tabular-nums">
            {student.attemptCount}
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="text-xs font-semibold uppercase tracking-wide text-foreground-muted">
            {t("Best score")}
          </dt>
          <dd className="mt-1 font-semibold tabular-nums">
            {student.bestResult === null
              ? "—"
              : `${student.bestResult.percentageDisplay}%`}
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="text-xs font-semibold uppercase tracking-wide text-foreground-muted">
            {t("Average")}
          </dt>
          <dd className="mt-1 font-semibold tabular-nums">
            {student.averageResult === null
              ? "—"
              : `${student.averageResult.scoreDisplay} / 10`}
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="text-xs font-semibold uppercase tracking-wide text-foreground-muted">
            {t("Current attempt")}
          </dt>
          <dd className="mt-1">
            <StateBadge
              variant={attemptStateVariants[student.latestAttempt.state]}
            >
              {localize(attemptStateLabels[student.latestAttempt.state])}
            </StateBadge>
          </dd>
        </div>
      </dl>
      {failure !== null ? (
        <Alert title={t("Attempt history could not be read")} variant="warning">
          {localize(failure)}
        </Alert>
      ) : null}
      {loading ? (
        <div className="flex items-center gap-3" role="status">
          <LoadingIndicator label={t("Loading attempt history")} />
          {t("Loading attempt history…")}
        </div>
      ) : rows.length === 0 && failure === null ? (
        <p className="text-sm text-foreground-secondary">
          {t("This student has no attempts in this assessment.")}
        </p>
      ) : (
        <>
          <Surface
            aria-label={t("Attempt history table")}
            className="hidden p-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 md:block"
            role="region"
            tabIndex={0}
          >
            <table className="w-full text-left text-sm">
              <caption className="sr-only">
                {t(
                  "Attempt history with state, timing, outcome and exact graded scores",
                )}
              </caption>
              <thead>
                <tr className="border-b border-border text-xs uppercase tracking-wide text-foreground-muted">
                  <th className="px-4 py-3 font-semibold" scope="col">
                    {t("Attempt")}
                  </th>
                  <th className="px-4 py-3 font-semibold" scope="col">
                    {t("State")}
                  </th>
                  <th
                    className="hidden px-4 py-3 font-semibold md:table-cell"
                    scope="col"
                  >
                    {t("Started")}
                  </th>
                  <th
                    className="hidden px-4 py-3 font-semibold md:table-cell"
                    scope="col"
                  >
                    {t("Finished")}
                  </th>
                  <th
                    className="hidden px-4 py-3 font-semibold lg:table-cell"
                    scope="col"
                  >
                    {t("Outcome")}
                  </th>
                  <th className="px-4 py-3 font-semibold" scope="col">
                    {t("Score")}
                  </th>
                  <th className="px-4 py-3 font-semibold" scope="col">
                    {t("Details")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((attempt) => (
                  <tr
                    className="border-b border-border/60 last:border-b-0"
                    key={attempt.attemptId}
                  >
                    <td className="px-4 py-3">
                      <span className="font-mono text-xs tabular-nums">
                        #{attempt.ordinal}
                      </span>
                      {attempt.attemptId === bestAttemptId ? (
                        <span className="mt-1 block text-xs font-semibold text-success">
                          {t("Best attempt")}
                        </span>
                      ) : null}
                      {attempt.attemptId === latestAttemptId ? (
                        <span className="mt-1 block text-xs font-semibold text-info">
                          {t("Latest attempt")}
                        </span>
                      ) : null}
                    </td>
                    <td className="px-4 py-3">
                      <StateBadge variant={attemptStateVariants[attempt.state]}>
                        {localize(attemptStateLabels[attempt.state])}
                      </StateBadge>
                    </td>
                    <td className="hidden px-4 py-3 text-xs tabular-nums md:table-cell">
                      {formatInstant(locale, attempt.startedAt)}
                    </td>
                    <td className="hidden px-4 py-3 text-xs tabular-nums md:table-cell">
                      {attempt.finalizedAt === null
                        ? "—"
                        : formatInstant(locale, attempt.finalizedAt)}
                      {attempt.deadlineAt !== null &&
                      attempt.state === "ACTIVE" ? (
                        <span className="mt-1 block text-foreground-muted">
                          {t("Due {time}", {
                            time: formatInstant(locale, attempt.deadlineAt),
                          })}
                        </span>
                      ) : null}
                    </td>
                    <td className="hidden px-4 py-3 text-xs lg:table-cell">
                      {attempt.terminalReason === null
                        ? "—"
                        : localize(
                            terminalAttemptLabels[attempt.terminalReason],
                          )}
                    </td>
                    <td className="px-4 py-3 tabular-nums">
                      {attempt.result === null ? (
                        <span className="text-foreground-muted">
                          {attempt.state === "ACTIVE"
                            ? t("No result (still active)")
                            : t("No result (ungraded)")}
                        </span>
                      ) : (
                        <span>
                          <span className="font-semibold">
                            {attempt.result.percentageDisplay}%
                          </span>
                          <span className="ml-2 font-mono text-xs text-foreground-secondary">
                            {attempt.result.earnedWeight} /{" "}
                            {attempt.result.possibleWeight}
                          </span>
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <AttemptDetailLinks
                        attempt={attempt}
                        publicationId={publicationId}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Surface>
          <ul aria-label={t("Attempt history")} className="space-y-3 md:hidden">
            {rows.map((attempt) => (
              <li key={attempt.attemptId}>
                <Surface className="space-y-3 p-4">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-xs tabular-nums">
                      #{attempt.ordinal}
                    </span>
                    <StateBadge variant={attemptStateVariants[attempt.state]}>
                      {localize(attemptStateLabels[attempt.state])}
                    </StateBadge>
                    {attempt.attemptId === bestAttemptId ? (
                      <span className="text-xs font-semibold text-success">
                        {t("Best attempt")}
                      </span>
                    ) : null}
                    {attempt.attemptId === latestAttemptId ? (
                      <span className="text-xs font-semibold text-info">
                        {t("Latest attempt")}
                      </span>
                    ) : null}
                  </div>
                  <dl className="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
                    <div className="min-w-0">
                      <dt className="text-xs text-foreground-muted">
                        {t("Started")}
                      </dt>
                      <dd className="mt-0.5 tabular-nums">
                        {formatInstant(locale, attempt.startedAt)}
                      </dd>
                    </div>
                    <div className="min-w-0">
                      <dt className="text-xs text-foreground-muted">
                        {t("Finished")}
                      </dt>
                      <dd className="mt-0.5 tabular-nums">
                        {attempt.finalizedAt === null
                          ? "—"
                          : formatInstant(locale, attempt.finalizedAt)}
                        {attempt.deadlineAt !== null &&
                        attempt.state === "ACTIVE" ? (
                          <span className="mt-1 block text-foreground-muted">
                            {t("Due {time}", {
                              time: formatInstant(locale, attempt.deadlineAt),
                            })}
                          </span>
                        ) : null}
                      </dd>
                    </div>
                    <div className="min-w-0">
                      <dt className="text-xs text-foreground-muted">
                        {t("Outcome")}
                      </dt>
                      <dd className="mt-0.5">
                        {attempt.terminalReason === null
                          ? "—"
                          : localize(
                              terminalAttemptLabels[attempt.terminalReason],
                            )}
                      </dd>
                    </div>
                    <div className="min-w-0">
                      <dt className="text-xs text-foreground-muted">
                        {t("Score")}
                      </dt>
                      <dd className="mt-0.5 tabular-nums">
                        {attempt.result === null
                          ? attempt.state === "ACTIVE"
                            ? t("No result (still active)")
                            : t("No result (ungraded)")
                          : `${attempt.result.percentageDisplay}% · ${attempt.result.earnedWeight} / ${attempt.result.possibleWeight}`}
                      </dd>
                    </div>
                  </dl>
                  <AttemptDetailLinks
                    attempt={attempt}
                    publicationId={publicationId}
                  />
                </Surface>
              </li>
            ))}
          </ul>
        </>
      )}
      {nextCursor !== null && !loading ? (
        <div className="flex justify-center">
          <Button
            disabled={loadingMore}
            isLoading={loadingMore}
            loadingLabel={t("Loading more attempts")}
            onClick={() => onLoadMore(nextCursor)}
            variant="secondary"
          >
            {t("Load more attempts")}
          </Button>
        </div>
      ) : null}
    </Surface>
  );
}

/** Authorized per-attempt navigation: Teacher Review only for graded
 * SUBMITTED attempts; Activity Evidence always revalidates its own access. */
function AttemptDetailLinks({
  attempt,
  publicationId,
}: {
  readonly attempt: TeacherStudentSummary["latestAttempt"];
  readonly publicationId: string;
}) {
  const { t } = useI18n();
  return (
    <span className="flex flex-col items-start gap-2">
      {attempt.teacherReviewAvailable ? (
        <Link
          className="rounded font-semibold text-primary underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus"
          href={`/app/assessments/${publicationId}/attempts/${attempt.attemptId}/review`}
        >
          {t("Review submitted work")}
        </Link>
      ) : null}
      <Link
        aria-label={t("Open activity evidence for Attempt {ordinal}", {
          ordinal: attempt.ordinal,
        })}
        className="rounded font-semibold text-primary underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus"
        href={monitoringHrefForAttempt(attempt.attemptId)}
      >
        {t("Activity evidence")}
      </Link>
    </span>
  );
}

const terminalAttemptLabels: Record<
  NonNullable<TeacherStudentSummary["latestAttempt"]["terminalReason"]>,
  string
> = {
  MANUAL_SUBMIT: "Manual submission",
  BOUNDED_TIMEOUT: "Bounded timeout",
  TEACHER_NOTICE_CUTOFF: "Teacher notice cutoff",
  STUDENT_ABANDON: "Student abandoned",
};
