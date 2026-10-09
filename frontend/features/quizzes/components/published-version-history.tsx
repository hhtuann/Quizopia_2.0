"use client";

import {
  type KeyboardEvent,
  type RefObject,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { Surface } from "../../../components/ui/surface";
import type {
  QuizApiClient,
  QuizApiFailure,
  QuizVersionDetail,
  QuizVersionSummary,
} from "../api/quiz-api-client";
import { QuizPreview } from "./quiz-preview";

const HISTORY_PAGE_SIZE = 20;

type HistoryPhase = "idle" | "loading" | "ready" | "error";
type DetailPhase = "idle" | "loading" | "ready" | "error";

interface HistoryState {
  readonly failure: QuizApiFailure | null;
  readonly items: readonly QuizVersionSummary[];
  readonly nextCursor: string | null;
  readonly phase: HistoryPhase;
  readonly requestKey: string;
}

interface DetailState {
  readonly detail: QuizVersionDetail | null;
  readonly failure: QuizApiFailure | null;
  readonly phase: DetailPhase;
  readonly requestKey: string;
  readonly versionNumber: number | null;
}

export interface PublishedVersionHistoryProps {
  readonly client: QuizApiClient;
  readonly isOpen: boolean;
  readonly onClose: () => void;
  readonly quizId: string;
  readonly refreshVersion: number;
  readonly triggerRef: RefObject<HTMLButtonElement | null>;
}

function failureMessage(failure: QuizApiFailure): string {
  if (failure.kind === "api-error") {
    return failure.error.message;
  }
  if (failure.kind === "transport-error") {
    return "Quizopia could not reach the Quiz service. Try again.";
  }
  if (failure.kind === "authentication-failure") {
    return "Your authenticated session is not available for this request.";
  }
  if (failure.kind === "validation-error") {
    return "The Quiz service returned an unexpected validation response.";
  }
  return `The Quiz service returned an unexpected response (${failure.status}).`;
}

function appendUniqueVersions(
  current: readonly QuizVersionSummary[],
  incoming: readonly QuizVersionSummary[],
): readonly QuizVersionSummary[] {
  const seen = new Set(current.map((item) => item.versionNumber));
  return [
    ...current,
    ...incoming.filter((item) => {
      if (seen.has(item.versionNumber)) {
        return false;
      }
      seen.add(item.versionNumber);
      return true;
    }),
  ];
}

function formatPublishedAt(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf())
    ? value
    : new Intl.DateTimeFormat(undefined, {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(parsed);
}

function focusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(
    container.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ),
  ).filter((element) => !element.hasAttribute("hidden"));
}

export function PublishedVersionHistory({
  client,
  isOpen,
  onClose,
  quizId,
  refreshVersion,
  triggerRef,
}: PublishedVersionHistoryProps) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const detailHeadingRef = useRef<HTMLHeadingElement>(null);
  const historyRequestRef = useRef<{
    readonly controller: AbortController;
    readonly key: string;
    readonly promise: ReturnType<QuizApiClient["listPublishedVersions"]>;
  } | null>(null);
  const detailRequestRef = useRef<{
    readonly controller: AbortController;
    readonly key: string;
    readonly promise: ReturnType<QuizApiClient["getPublishedVersion"]>;
  } | null>(null);
  const paginationControllerRef = useRef<AbortController | null>(null);
  const [retryVersion, setRetryVersion] = useState(0);
  const [historyState, setHistoryState] = useState<HistoryState>({
    failure: null,
    items: [],
    nextCursor: null,
    phase: "idle",
    requestKey: "",
  });
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [paginationFailure, setPaginationFailure] =
    useState<QuizApiFailure | null>(null);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [detailRetryVersion, setDetailRetryVersion] = useState(0);
  const detailCacheRef = useRef<Record<string, QuizVersionDetail>>({});
  const [detailState, setDetailState] = useState<DetailState>({
    detail: null,
    failure: null,
    phase: "idle",
    requestKey: "",
    versionNumber: null,
  });

  const historyRequestKey = `${quizId}:${refreshVersion}:${retryVersion}`;
  const visibleHistoryState =
    historyState.requestKey === historyRequestKey
      ? historyState
      : {
          failure: null,
          items: [],
          nextCursor: null,
          phase: "loading" as const,
          requestKey: historyRequestKey,
        };
  const selectedSummary = useMemo(
    () =>
      visibleHistoryState.items.find(
        (item) => item.versionNumber === selectedVersion,
      ) ?? null,
    [selectedVersion, visibleHistoryState.items],
  );

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    const trigger = triggerRef.current;
    const frame = window.requestAnimationFrame(() =>
      closeButtonRef.current?.focus(),
    );
    return () => {
      window.cancelAnimationFrame(frame);
      trigger?.focus();
    };
  }, [isOpen, triggerRef]);

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    let request = historyRequestRef.current;
    if (request?.key !== historyRequestKey) {
      request?.controller.abort();
      const controller = new AbortController();
      request = {
        controller,
        key: historyRequestKey,
        promise: client.listPublishedVersions(
          quizId,
          { limit: HISTORY_PAGE_SIZE },
          controller.signal,
        ),
      };
      historyRequestRef.current = request;
      paginationControllerRef.current?.abort();
      paginationControllerRef.current = null;
      setIsLoadingMore(false);
      setHistoryState({
        failure: null,
        items: [],
        nextCursor: null,
        phase: "loading",
        requestKey: historyRequestKey,
      });
      setPaginationFailure(null);
      setSelectedVersion(null);
    }

    let active = true;
    void request.promise.then((result) => {
      if (!active || request.controller.signal.aborted) {
        return;
      }
      if (!result.ok) {
        setHistoryState({
          failure: result.error,
          items: [],
          nextCursor: null,
          phase: "error",
          requestKey: historyRequestKey,
        });
        return;
      }
      setHistoryState({
        failure: null,
        items: result.value.items,
        nextCursor: result.value.nextCursor,
        phase: "ready",
        requestKey: historyRequestKey,
      });
    });

    return () => {
      active = false;
    };
  }, [client, historyRequestKey, isOpen, quizId]);

  useEffect(() => {
    if (!isOpen || selectedVersion === null) {
      return;
    }

    const detailKey = `${quizId}:${selectedVersion}`;
    const requestKey = `${detailKey}:${detailRetryVersion}`;
    const cached = detailCacheRef.current[detailKey];
    if (cached !== undefined && detailRetryVersion === 0) {
      setDetailState({
        detail: cached,
        failure: null,
        phase: "ready",
        requestKey,
        versionNumber: selectedVersion,
      });
      return;
    }

    let request = detailRequestRef.current;
    if (request?.key !== requestKey) {
      request?.controller.abort();
      const controller = new AbortController();
      request = {
        controller,
        key: requestKey,
        promise: client.getPublishedVersion(
          quizId,
          selectedVersion,
          controller.signal,
        ),
      };
      detailRequestRef.current = request;
      setDetailState({
        detail: null,
        failure: null,
        phase: "loading",
        requestKey,
        versionNumber: selectedVersion,
      });
    }

    let active = true;
    void request.promise.then((result) => {
      if (!active || request.controller.signal.aborted) {
        return;
      }
      if (!result.ok) {
        setDetailState({
          detail: null,
          failure: result.error,
          phase: "error",
          requestKey,
          versionNumber: selectedVersion,
        });
        return;
      }
      detailCacheRef.current[detailKey] = result.value;
      setDetailState({
        detail: result.value,
        failure: null,
        phase: "ready",
        requestKey,
        versionNumber: selectedVersion,
      });
    });

    return () => {
      active = false;
    };
  }, [client, detailRetryVersion, isOpen, quizId, selectedVersion]);

  useEffect(() => {
    if (detailState.phase === "ready") {
      detailHeadingRef.current?.focus();
    }
  }, [detailState.phase, detailState.requestKey]);

  useEffect(
    () => () => {
      historyRequestRef.current?.controller.abort();
      detailRequestRef.current?.controller.abort();
      paginationControllerRef.current?.abort();
    },
    [],
  );

  async function loadMore() {
    const cursor = visibleHistoryState.nextCursor;
    if (cursor === null || paginationControllerRef.current !== null) {
      return;
    }

    const controller = new AbortController();
    paginationControllerRef.current = controller;
    setIsLoadingMore(true);
    setPaginationFailure(null);
    const requestKey = historyRequestKey;
    const result = await client.listPublishedVersions(
      quizId,
      { cursor, limit: HISTORY_PAGE_SIZE },
      controller.signal,
    );
    if (controller.signal.aborted) {
      return;
    }
    paginationControllerRef.current = null;
    setIsLoadingMore(false);
    if (!result.ok) {
      setPaginationFailure(result.error);
      return;
    }
    setHistoryState((current) =>
      current.requestKey === requestKey
        ? {
            failure: null,
            items: appendUniqueVersions(current.items, result.value.items),
            nextCursor: result.value.nextCursor,
            phase: "ready",
            requestKey,
          }
        : current,
    );
  }

  function selectVersion(versionNumber: number) {
    if (selectedVersion === versionNumber) {
      return;
    }
    detailRequestRef.current?.controller.abort();
    setDetailRetryVersion(0);
    setSelectedVersion(versionNumber);
  }

  function handleDialogKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== "Tab" || dialogRef.current === null) {
      return;
    }
    const focusable = focusableElements(dialogRef.current);
    const first = focusable[0];
    const last = focusable.at(-1);
    if (first === undefined || last === undefined) {
      return;
    }
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  if (!isOpen) {
    return null;
  }

  return (
    <div
      className="fixed inset-0 z-50 flex bg-foreground/35 p-2 sm:p-4"
      onMouseDown={(event) => {
        if (event.currentTarget === event.target) {
          onClose();
        }
      }}
    >
      <Surface
        aria-labelledby="published-history-title"
        aria-modal="true"
        className="m-auto flex h-full max-h-[56rem] w-full max-w-7xl flex-col overflow-hidden shadow-card"
        onKeyDown={handleDialogKeyDown}
        ref={dialogRef}
        role="dialog"
      >
        <header className="flex shrink-0 flex-wrap items-center gap-3 border-b border-border px-4 py-3 sm:px-5">
          <div className="min-w-0 flex-1">
            <h2
              className="font-heading text-xl font-bold text-foreground"
              id="published-history-title"
            >
              Published versions
            </h2>
            <p className="mt-1 text-sm text-foreground-muted">
              Immutable snapshots. Previewing a version never changes your
              current draft.
            </p>
          </div>
          <Button
            aria-label="Close published versions"
            className="h-11 w-11 shrink-0 p-0"
            onClick={onClose}
            ref={closeButtonRef}
            variant="secondary"
          >
            <svg
              aria-hidden="true"
              className="h-5 w-5"
              fill="none"
              stroke="currentColor"
              strokeLinecap="round"
              strokeWidth="2"
              viewBox="0 0 24 24"
            >
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </Button>
        </header>

        <div className="grid min-h-0 flex-1 lg:grid-cols-[20rem_minmax(0,1fr)]">
          <aside className="scrollbar-brand scrollbar-brand-gutter min-h-0 overflow-y-auto border-b border-border bg-surface-muted/50 p-3 sm:p-4 lg:border-b-0 lg:border-r">
            {visibleHistoryState.phase === "loading" ? (
              <div
                className="flex min-h-40 items-center justify-center gap-3"
                role="status"
              >
                <LoadingIndicator label="Loading published versions" />
                <span className="text-sm font-medium text-foreground-secondary">
                  Loading versions...
                </span>
              </div>
            ) : visibleHistoryState.phase === "error" &&
              visibleHistoryState.failure !== null ? (
              <div>
                <Alert
                  title="Version history could not be loaded"
                  variant="danger"
                >
                  {failureMessage(visibleHistoryState.failure)}
                </Alert>
                <Button
                  className="mt-4 w-full"
                  onClick={() => setRetryVersion((current) => current + 1)}
                  variant="secondary"
                >
                  Retry history
                </Button>
              </div>
            ) : visibleHistoryState.items.length === 0 ? (
              <div className="rounded-xl border border-dashed border-border-strong bg-surface p-5">
                <h3 className="text-base font-semibold text-foreground">
                  No published versions yet
                </h3>
                <p className="mt-2 text-sm leading-6 text-foreground-muted">
                  Publish the current draft to create its first immutable
                  snapshot.
                </p>
              </div>
            ) : (
              <>
                <ol
                  aria-label="Published version history"
                  className="space-y-2"
                >
                  {visibleHistoryState.items.map((item) => {
                    const selected = item.versionNumber === selectedVersion;
                    return (
                      <li key={item.id}>
                        <button
                          aria-current={selected ? "true" : undefined}
                          className={`min-h-11 w-full rounded-lg border p-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus motion-reduce:transition-none ${
                            selected
                              ? "border-primary bg-primary/10"
                              : "border-border bg-surface hover:border-primary/40 hover:bg-primary/5"
                          }`}
                          onClick={() => selectVersion(item.versionNumber)}
                          type="button"
                        >
                          <span className="flex items-center justify-between gap-2">
                            <span className="font-semibold text-foreground">
                              Version {item.versionNumber}
                            </span>
                            <span className="rounded bg-surface-muted px-2 py-1 font-mono text-[0.6875rem] font-semibold text-foreground-muted">
                              Schema {item.contentSchemaVersion}
                            </span>
                          </span>
                          <span className="mt-1 block truncate text-sm text-foreground-secondary">
                            {item.titleSnapshot?.trim() || "Untitled quiz"}
                          </span>
                          <time
                            className="mt-1 block text-xs text-foreground-muted"
                            dateTime={item.createdAt}
                          >
                            {formatPublishedAt(item.createdAt)}
                          </time>
                        </button>
                      </li>
                    );
                  })}
                </ol>

                {paginationFailure !== null ? (
                  <Alert
                    className="mt-4"
                    title="More versions could not be loaded"
                    variant="danger"
                  >
                    {failureMessage(paginationFailure)}
                  </Alert>
                ) : null}

                {visibleHistoryState.nextCursor !== null ? (
                  <Button
                    className="mt-4 w-full"
                    isLoading={isLoadingMore}
                    loadingLabel="Loading more versions"
                    onClick={() => void loadMore()}
                    variant="secondary"
                  >
                    Load more versions
                  </Button>
                ) : (
                  <p
                    className="mt-4 text-center text-xs text-foreground-muted"
                    role="status"
                  >
                    All published versions loaded.
                  </p>
                )}
              </>
            )}
          </aside>

          <main className="scrollbar-brand scrollbar-brand-gutter min-h-0 overflow-y-auto bg-surface-muted/30 p-3 sm:p-5">
            {selectedVersion === null ? (
              <div className="flex min-h-64 items-center justify-center">
                <div className="max-w-md text-center">
                  <h3 className="font-heading text-xl font-bold text-foreground">
                    Select a published version
                  </h3>
                  <p className="mt-2 text-sm leading-6 text-foreground-muted">
                    Snapshot source and content load only when you open a
                    version.
                  </p>
                </div>
              </div>
            ) : detailState.phase === "loading" ||
              detailState.versionNumber !== selectedVersion ? (
              <div
                className="flex min-h-64 items-center justify-center gap-3"
                role="status"
              >
                <LoadingIndicator
                  label={`Loading version ${selectedVersion}`}
                />
                <span className="text-sm font-medium text-foreground-secondary">
                  Loading version {selectedVersion}...
                </span>
              </div>
            ) : detailState.phase === "error" &&
              detailState.failure !== null ? (
              <div className="mx-auto max-w-xl">
                <Alert
                  title={`Version ${selectedVersion} could not be loaded`}
                  variant="danger"
                >
                  {failureMessage(detailState.failure)}
                </Alert>
                <Button
                  className="mt-4"
                  onClick={() =>
                    setDetailRetryVersion((current) => current + 1)
                  }
                  variant="secondary"
                >
                  Retry version
                </Button>
              </div>
            ) : detailState.detail !== null && selectedSummary !== null ? (
              <div className="mx-auto max-w-4xl">
                <div className="mb-4 rounded-xl border border-border bg-surface p-4 sm:p-5">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="text-sm font-semibold text-primary">
                        Immutable version {detailState.detail.versionNumber}
                      </p>
                      <h3
                        className="mt-1 break-words font-heading text-2xl font-bold text-foreground outline-none focus-visible:ring-2 focus-visible:ring-focus"
                        ref={detailHeadingRef}
                        tabIndex={-1}
                      >
                        {detailState.detail.titleSnapshot?.trim() ||
                          "Untitled quiz"}
                      </h3>
                    </div>
                    <time
                      className="text-xs font-medium text-foreground-muted"
                      dateTime={detailState.detail.createdAt}
                    >
                      {formatPublishedAt(detailState.detail.createdAt)}
                    </time>
                  </div>
                  {detailState.detail.descriptionSnapshot?.trim() ? (
                    <p className="mt-3 text-sm leading-6 text-foreground-secondary">
                      {detailState.detail.descriptionSnapshot}
                    </p>
                  ) : null}
                </div>
                <div className="rounded-xl border border-border bg-surface p-4 sm:p-5">
                  <QuizPreview
                    description="Read-only preview from this immutable published snapshot. Current Draft content is not used here."
                    readOnly
                    source={detailState.detail.sourceSnapshot}
                    title={`Published preview · Version ${detailState.detail.versionNumber}`}
                  />
                </div>
              </div>
            ) : null}
          </main>
        </div>
      </Surface>
    </div>
  );
}
