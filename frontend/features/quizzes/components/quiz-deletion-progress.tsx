"use client";

import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { useI18n } from "../../../lib/i18n/provider";
import type { QuizDeletionOperationStatus } from "../api/quiz-deletion-client";
import {
  deletionProgressStep,
  isDeletionStatusTerminal,
  type DeletionProgressStep,
} from "../model/quiz-deletion";

/** Bounded polling cadence; stopped at terminal states and on poll errors. */
export const DELETION_POLL_INTERVAL_MS = 3_000;

export function deletionStepTextKey(step: DeletionProgressStep): string {
  switch (step) {
    case "revoking-access":
      return "Revoking access";
    case "deleting-data":
      return "Access blocked · Deleting data";
    case "waiting-retention":
      return "Waiting for the mandatory retention period";
    case "retrying":
      return "Quizopia is retrying automatically; nothing needs your attention right now.";
    case "primary-deleted":
      return "Primary data deleted";
    case "primary-deleted-copies-pending":
      return "Primary data deleted · Waiting for backup copies to be erased";
    case "all-copies-erased":
      return "Fully deleted";
  }
}

type DeletionStatusReader = (
  signal?: AbortSignal,
) => Promise<
  | { readonly ok: true; readonly value: QuizDeletionOperationStatus }
  | { readonly ok: false; readonly error: { readonly kind: string } }
>;

export interface QuizDeletionProgressProps {
  readonly getDeletionStatus: DeletionStatusReader;
  /** Status accepted with the 202 (or last known); rendered before first poll. */
  readonly initialStatus?: string;
  readonly initialNote?: string | null;
  /** Fired once when the operation reaches a terminal state. */
  readonly onTerminal?: (status: string) => void;
  readonly onClose?: () => void;
}

interface KnownStatus {
  readonly status: string;
  readonly lastReason: string | null;
  readonly note: string | null;
}

export function QuizDeletionProgress({
  getDeletionStatus,
  initialStatus,
  initialNote,
  onTerminal,
  onClose,
}: QuizDeletionProgressProps) {
  const { localize, t } = useI18n();
  const titleId = useId();
  const [operation, setOperation] = useState<KnownStatus | null>(
    initialStatus === undefined
      ? null
      : { status: initialStatus, lastReason: null, note: initialNote ?? null },
  );
  const [isPollFailed, setIsPollFailed] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [pollVersion, setPollVersion] = useState(0);
  const statusRef = useRef(operation);
  // Parents routinely pass inline closures; the poll loop must not restart
  // on every re-render, so the reader is read through refs kept current in
  // layout effects (which run before the polling effect).
  const readerRef = useRef(getDeletionStatus);
  useLayoutEffect(() => {
    statusRef.current = operation;
    readerRef.current = getDeletionStatus;
  }, [operation, getDeletionStatus]);
  const onTerminalRef = useRef(onTerminal);
  useEffect(() => {
    onTerminalRef.current = onTerminal;
  }, [onTerminal]);

  const status = operation?.status ?? null;
  const isTerminal = status !== null && isDeletionStatusTerminal(status);
  const step =
    status === null ? null : deletionProgressStep(status, operation?.note);

  useEffect(() => {
    const controller = new AbortController();
    let cancelled = false;
    let timer: number | undefined;

    function handleKnown(next: KnownStatus) {
      setOperation(next);
      if (isDeletionStatusTerminal(next.status)) {
        onTerminalRef.current?.(next.status);
        return;
      }
      timer = window.setTimeout(() => void poll(), DELETION_POLL_INTERVAL_MS);
    }

    async function poll() {
      const result = await readerRef.current(controller.signal);
      if (cancelled || controller.signal.aborted) {
        return;
      }
      if (!result.ok) {
        // Stop automatic polling on errors; the explicit refresh button
        // restarts it without losing the last known status.
        setIsPollFailed(true);
        return;
      }
      setIsPollFailed(false);
      handleKnown({
        status: result.value.status,
        lastReason: result.value.lastReason,
        note: result.value.note ?? null,
      });
    }

    const initial = statusRef.current;
    if (initial !== null && isDeletionStatusTerminal(initial.status)) {
      onTerminalRef.current?.(initial.status);
    } else {
      void poll();
    }

    return () => {
      cancelled = true;
      controller.abort();
      if (timer !== undefined) {
        window.clearTimeout(timer);
      }
    };
  }, [pollVersion]);

  async function refreshManually() {
    if (isRefreshing) {
      return;
    }
    setIsRefreshing(true);
    const result = await readerRef.current();
    setIsRefreshing(false);
    if (!result.ok) {
      setIsPollFailed(true);
      return;
    }
    setIsPollFailed(false);
    const next: KnownStatus = {
      status: result.value.status,
      lastReason: result.value.lastReason,
      note: result.value.note ?? null,
    };
    setOperation(next);
    if (isDeletionStatusTerminal(next.status)) {
      onTerminalRef.current?.(next.status);
      return;
    }
    // Restart the bounded automatic polling loop.
    setPollVersion((current) => current + 1);
  }

  return (
    <section
      aria-labelledby={titleId}
      className="rounded-xl border border-danger/30 bg-danger/5 p-4 sm:p-5"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="text-base font-semibold text-foreground" id={titleId}>
            {t("Deletion progress")}
          </h3>
          <div aria-live="polite" className="mt-1 flex items-center gap-2">
            {step === null || !isTerminal ? (
              <LoadingIndicator label={t("Refreshing deletion status")} />
            ) : null}
            <p className="text-sm font-semibold text-foreground-secondary">
              {step === null
                ? t("Refreshing deletion status")
                : localize(deletionStepTextKey(step))}
            </p>
          </div>
          {operation?.lastReason ? (
            <p className="mt-2 break-words text-sm text-foreground-muted">
              {localize("Latest note: {reason}", {
                reason: operation.lastReason,
              })}
            </p>
          ) : null}
        </div>
        <div className="flex shrink-0 flex-wrap gap-2">
          {isPollFailed ? (
            <Button
              isLoading={isRefreshing}
              loadingLabel={t("Refreshing deletion status")}
              onClick={() => void refreshManually()}
              size="compact"
              variant="secondary"
            >
              {/* Cross-catalog key owned by the assessment catalog. */}
              {localize("Refresh status")}
            </Button>
          ) : null}
          {onClose ? (
            <Button onClick={onClose} size="compact" variant="ghost">
              {t("Close deletion progress")}
            </Button>
          ) : null}
        </div>
      </div>
      {isPollFailed ? (
        <Alert
          className="mt-3"
          title={t("The deletion status could not be refreshed.")}
          variant="warning"
        >
          {t(
            "The last known status is shown. Refresh to keep following the deletion.",
          )}
        </Alert>
      ) : null}
    </section>
  );
}
