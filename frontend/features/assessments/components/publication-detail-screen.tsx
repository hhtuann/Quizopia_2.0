"use client";
import Link from "next/link";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import { Alert } from "../../../components/ui/alert";
import { Button, OUTLINE_LINK_CLASSES } from "../../../components/ui/button";
import { ChevronDownIcon } from "../../../components/ui/icons";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { StateBadge } from "../../../components/ui/state-badge";
import { Surface } from "../../../components/ui/surface";
import { useI18n } from "../../../lib/i18n/provider";
import {
  isUncertain,
  publicationFailureMessage,
  type PublicationApiClient,
  type PublicationFailure,
  type PublicationResult,
} from "../api/publication-api-client";
import type {
  OwnerView,
  ClosedPublication,
  Update,
  Open,
  Schedule,
  TeacherCommand,
} from "../api/publication-contract.generated";
import {
  newIdempotencyKey,
  publicationStateVariants,
  sameConfiguration,
} from "../model/publication";
import { instantMicroseconds } from "../utils/utc-time";
import {
  useRealtimeResync,
  useRealtimeSubscriptions,
} from "../../realtime/hooks/use-realtime-subscriptions";
import { RealtimeStatusInline } from "../../realtime/components/realtime-status";
import type { RealtimeResource } from "../../realtime/model/realtime-frame";
import { useTeacherResultsClient } from "../../teacher-results/hooks/use-teacher-results-client";
import { PublicationResultsSection } from "../../teacher-results/components/teacher-results-pages";
import { PrivatePublicationLocator } from "./private-publication-locator";
import { PublicationConfigurationForm } from "./publication-configuration-form";
import { PublicationConfirmation } from "./publication-confirmation";
import { PublicationSummary } from "./publication-summary";
import { ScheduleExtension } from "./publication-schedule-extension";

type Command =
  | { readonly kind: "update"; readonly input: Update }
  | { readonly kind: "open"; readonly input: Open }
  | { readonly kind: "extend"; readonly input: Schedule }
  | {
      readonly kind: "close";
      readonly input: TeacherCommand;
      readonly key: string;
    };

function commandSatisfied(command: Command, publication: OwnerView): boolean {
  if (command.kind === "close") return publication.state === "CLOSED";
  if (command.kind === "open") return publication.state !== "DRAFT";
  if (publication.revision <= command.input.expectedRevision) return false;
  if (command.kind === "update")
    return sameConfiguration(
      publication.configuration,
      command.input.configuration,
    );
  return (
    instantMicroseconds(publication.configuration.endAt) ===
      instantMicroseconds(command.input.endAt) &&
    publication.configuration.durationMinutes === command.input.durationMinutes
  );
}

const DESKTOP_SUMMARY_QUERY = "(min-width: 1024px)";

function subscribeDesktopQuery(callback: () => void) {
  if (
    typeof window === "undefined" ||
    typeof window.matchMedia !== "function"
  ) {
    return () => {};
  }
  const query = window.matchMedia(DESKTOP_SUMMARY_QUERY);
  query.addEventListener("change", callback);
  return () => query.removeEventListener("change", callback);
}

function desktopQuerySnapshot(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia(DESKTOP_SUMMARY_QUERY).matches
  );
}

/**
 * Collapsible authoritative configuration card for the unified results-first
 * OPEN/CLOSED detail layout. The full
 * PublicationSummary (and the PUBLIC private locator) sits behind a native
 * <details>/<summary> disclosure that defaults to expanded on lg+ desktops
 * and collapsed on smaller viewports. The responsive default is read through
 * useSyncExternalStore (collapsed server snapshot, live matchMedia client
 * snapshot), the user's last toggle takes precedence, and the toggle itself
 * is fully controlled (native switch prevented) so mouse, keyboard and tests
 * share one source of truth. A locale switch never remounts or resets it.
 */
function ConfigurationSummaryCard({
  publication,
}: {
  readonly publication: OwnerView;
}) {
  const { t } = useI18n();
  const desktopDefault = useSyncExternalStore(
    subscribeDesktopQuery,
    desktopQuerySnapshot,
    () => false,
  );
  const [userOverride, setUserOverride] = useState<boolean | null>(null);
  const expanded = userOverride ?? desktopDefault;
  return (
    <Surface className="p-5 sm:p-6">
      <details
        onToggle={(event) => setUserOverride(event.currentTarget.open)}
        open={expanded}
      >
        <summary
          className="-ml-2 flex min-h-11 cursor-pointer list-none items-center gap-2 rounded-lg px-2 py-2 text-sm font-semibold text-primary hover:underline hover:decoration-primary/40 hover:underline-offset-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus [&::-webkit-details-marker]:hidden"
          onClick={(event) => {
            event.preventDefault();
            setUserOverride(!expanded);
          }}
        >
          <ChevronDownIcon
            className={`size-4 shrink-0 transition-transform duration-200 motion-reduce:transition-none ${expanded ? "rotate-180" : ""}`}
          />
          {t("Configuration summary")}
        </summary>
        <div className="mt-5 border-t border-border pt-5">
          <PublicationSummary configuration={publication.configuration} />
          {publication.configuration.audience === "PUBLIC" &&
          publication.privateLocator !== null ? (
            <div className="mt-5">
              <PrivatePublicationLocator
                key={`${publication.publicationId}:${publication.revision}`}
                locator={publication.privateLocator}
              />
            </div>
          ) : null}
        </div>
      </details>
    </Surface>
  );
}

export function PublicationDetailScreen({
  client,
  publicationId,
}: {
  readonly client: PublicationApiClient;
  readonly publicationId: string;
}) {
  const { t, localize } = useI18n();
  const resultsClient = useTeacherResultsClient();
  const lifetime = useRef<AbortController | null>(null);
  const readController = useRef<AbortController | null>(null);
  const readSequence = useRef(0);
  const inFlight = useRef(false);
  const [publication, setPublication] = useState<OwnerView | null>(null);
  const [loading, setLoading] = useState(true);
  const [readFailure, setReadFailure] = useState<PublicationFailure | null>(
    null,
  );
  const [mutationFailure, setMutationFailure] =
    useState<PublicationFailure | null>(null);
  const [pending, setPending] = useState<Command | null>(null);
  const [reconciled, setReconciled] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [confirmation, setConfirmation] = useState<"open" | "close" | null>(
    null,
  );
  const [editBase, setEditBase] = useState<OwnerView | null>(null);
  const [editReset, setEditReset] = useState(0);
  // Unsaved-value flags reported by the edit form / schedule extension so the
  // parent's destructive close controls (Cancel editing, Hide schedule
  // extension) can confirm instead of silently discarding entered values.
  // Remounting the child (key change / reopen) resets its report to false.
  const [editDirty, setEditDirty] = useState(false);
  const [extension, setExtension] = useState<OwnerView | null>(null);
  const [extensionDirty, setExtensionDirty] = useState(false);

  const refresh = useCallback((): Promise<OwnerView | null> => {
    const sequence = ++readSequence.current;
    readController.current?.abort();
    const controller = new AbortController();
    readController.current = controller;
    return client.get(publicationId, controller.signal).then((result) => {
      if (
        controller.signal.aborted ||
        lifetime.current?.signal.aborted ||
        sequence !== readSequence.current
      )
        return null;
      setLoading(false);
      if (!result.ok) {
        setReadFailure(result.error);
        return null;
      }
      setReadFailure(null);
      setPublication(result.value);
      return result.value;
    });
  }, [client, publicationId]);

  useEffect(() => {
    const scope = new AbortController();
    lifetime.current = scope;
    void refresh();
    return () => {
      scope.abort();
      readController.current?.abort();
    };
  }, [refresh]);

  // Best-effort teacher publication hints reconcile through the existing sequence-fenced
  // REST read; realtime never mutates publication state directly. PUBLICATION_TEACHER is
  // subscribed only while the authorized Publication is OPEN, so DRAFT→OPEN subscribes
  // and OPEN→CLOSED unsubscribes over the live connection without a manual reconnect.
  const publicationState = publication?.state;
  const realtimeResources = useMemo<readonly RealtimeResource[]>(
    () =>
      publicationState === "OPEN"
        ? [{ kind: "PUBLICATION_TEACHER" as const, id: publicationId }]
        : [],
    [publicationState, publicationId],
  );
  const realtimeResync = useRealtimeResync({ active: true, run: refresh });
  const realtimeStatus = useRealtimeSubscriptions({
    enabled: true,
    resources: realtimeResources,
    onSignal: realtimeResync.request,
  });

  async function send(command: Command) {
    if (
      inFlight.current ||
      lifetime.current?.signal.aborted ||
      (pending !== null && !reconciled)
    )
      return;
    inFlight.current = true;
    setBusy(true);
    setMutationFailure(null);
    setStatus("Sending reviewed operation…");
    const signal = lifetime.current?.signal;
    let result: PublicationResult<OwnerView | ClosedPublication>;
    switch (command.kind) {
      case "update":
        result = await client.update(publicationId, command.input, signal);
        break;
      case "open":
        result = await client.open(publicationId, command.input, signal);
        break;
      case "extend":
        result = await client.extend(publicationId, command.input, signal);
        break;
      case "close":
        result = await client.close(
          publicationId,
          command.input,
          command.key,
          signal,
        );
        break;
    }
    if (signal?.aborted) return;
    if (result.ok) {
      if ("configuration" in result.value) setPublication(result.value);
      else {
        const acknowledgement = result.value;
        setPublication((current) =>
          current
            ? {
                ...current,
                state: acknowledgement.state,
                revision: acknowledgement.revision,
              }
            : null,
        );
      }
      setPending(null);
      setEditBase(null);
      setExtension(null);
      setStatus(
        command.kind === "close"
          ? "The assessment is now CLOSED."
          : "Publication change confirmed.",
      );
    } else {
      setMutationFailure(result.error);
      const capacityRetry =
        command.kind === "close" &&
        result.error.kind === "api" &&
        result.error.status === 429;
      const retainCommand = isUncertain(result.error) || capacityRetry;
      setPending(retainCommand ? command : null);
      setReconciled(false);
      setStatus(
        capacityRetry
          ? "Too many requests. Checking the current state; retry repeats your original request."
          : retainCommand
            ? "Outcome unclear. Checking the current state before another action."
            : "Operation was not confirmed.",
      );
      if (
        (retainCommand ||
          (result.error.kind === "api" && result.error.status === 409)) &&
        !(
          result.error.kind === "unavailable" &&
          result.error.reason === "refresh"
        )
      ) {
        setLoading(true);
        const current = await refresh();
        if (signal?.aborted) return;
        if (current) setReconciled(true);
        if (current && retainCommand && commandSatisfied(command, current)) {
          setPending(null);
          setMutationFailure(null);
          setEditBase(null);
          setExtension(null);
          setStatus(
            "The latest details confirm the current state; nothing was repeated.",
          );
        }
      }
    }
    setBusy(false);
    inFlight.current = false;
  }

  async function reconcile() {
    setLoading(true);
    setReconciled(false);
    const current = await refresh();
    if (current) setReconciled(true);
    if (current && pending && commandSatisfied(pending, current)) {
      setPending(null);
      setMutationFailure(null);
      setEditBase(null);
      setExtension(null);
      setStatus(
        "The latest details confirm the current state; nothing was repeated.",
      );
    }
  }

  if (publication === null)
    return (
      <Surface className="p-6">
        <h1 className="font-heading text-3xl font-bold">
          {t("Publication details")}
        </h1>
        {loading ? (
          <div className="mt-5 flex items-center gap-3" role="status">
            <LoadingIndicator label={t("Loading Publication")} />
            {t("Loading details…")}
          </div>
        ) : (
          <>
            <Alert
              className="mt-5"
              title={t("Publication could not be loaded")}
              variant="warning"
            >
              {readFailure
                ? localize(publicationFailureMessage(readFailure))
                : t("Publication details are unavailable.")}
            </Alert>
            <Button
              className="mt-4"
              onClick={() => {
                setLoading(true);
                void refresh();
              }}
              variant="secondary"
            >
              {t("Retry Publication details")}
            </Button>
          </>
        )}
      </Surface>
    );

  const blocked = busy || loading || pending !== null;
  const editingConflict =
    editBase !== null &&
    (editBase.revision !== publication.revision ||
      publication.state !== "DRAFT");

  // Shared header: compact title/status. Results owns its explicit refresh.
  const header = (
    <header className="flex flex-wrap items-start justify-between gap-4">
      <div className="min-w-0">
        <h1 className="break-words font-heading text-3xl font-bold">
          {publication.configuration.title}
        </h1>
        <p className="mt-2 flex flex-wrap items-center gap-3 text-sm">
          {/* Canonical API state enum rendered verbatim by policy. */}
          <StateBadge variant={publicationStateVariants[publication.state]}>
            {publication.state}
          </StateBadge>
          <span className="text-foreground-muted">
            {t("Version {number}", {
              number: publication.configuration.versionNumber,
            })}
          </span>
        </p>
        <div className="mt-2">
          <RealtimeStatusInline status={realtimeStatus} />
        </div>
      </div>
    </header>
  );
  const statusArea = (
    <>
      <p
        aria-live="polite"
        className="text-sm font-medium text-foreground-secondary"
        role="status"
      >
        {localize(status)}
      </p>
      {readFailure ? (
        <Alert title={t("Could not refresh")} variant="warning">
          {localize(publicationFailureMessage(readFailure))}{" "}
          {t("The details below may be outdated.")}
          <Button
            className="mt-3"
            disabled={busy || loading}
            onClick={() => void reconcile()}
            variant="secondary"
          >
            {t("Retry Publication details")}
          </Button>
        </Alert>
      ) : null}
      {mutationFailure ? (
        <Alert
          title={
            pending &&
            mutationFailure.kind === "api" &&
            mutationFailure.status === 429
              ? t("Retrying safely")
              : pending
                ? t("Outcome unclear")
                : t("Action was not completed")
          }
          variant="warning"
        >
          {localize(publicationFailureMessage(mutationFailure))}
        </Alert>
      ) : null}
      {pending ? (
        <Surface className="space-y-3 border-warning p-5">
          <h2 className="font-semibold">{t("Check the latest state first")}</h2>
          <p className="text-sm leading-6">
            {t(
              "This action may have already succeeded. Refresh to see the current state; retrying repeats only your original request.",
            )}
          </p>
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={busy || loading}
              onClick={() => void reconcile()}
              variant="secondary"
            >
              {t("Check now")}
            </Button>
            <Button
              aria-label={busy ? t("Retrying…") : undefined}
              disabled={!reconciled || loading || readFailure !== null}
              isLoading={busy}
              loadingLabel={t("Retrying…")}
              onClick={() => void send(pending)}
            >
              {t("Retry")}
            </Button>
          </div>
        </Surface>
      ) : null}
    </>
  );

  const confirmationDialog = confirmation ? (
    <PublicationConfirmation
      confirmLabel={
        confirmation === "open" ? t("OPEN Publication") : t("CLOSE Publication")
      }
      destructive={confirmation === "close"}
      onCancel={() => setConfirmation(null)}
      onConfirm={() => {
        const action = confirmation;
        setConfirmation(null);
        void send(
          action === "open"
            ? {
                kind: "open",
                input: { expectedRevision: publication.revision },
              }
            : {
                kind: "close",
                input: { expectedRevision: publication.revision },
                key: newIdempotencyKey(),
              },
        );
      }}
      title={
        confirmation === "open"
          ? t("Review and OPEN Publication")
          : t("Confirm direct CLOSE")
      }
    >
      <PublicationSummary configuration={publication.configuration} />
      {confirmation === "open" ? (
        <p>
          {t(
            "Opening freezes the quiz version, audience and monitoring. Students can start when the participation window opens.",
          )}
        </p>
      ) : (
        <p>
          {t(
            "Closing ends new attempts. Students with active attempts may prevent closure; no one’s work is submitted or discarded by this action.",
          )}
        </p>
      )}
    </PublicationConfirmation>
  ) : null;

  if (publication.state === "DRAFT") {
    // DRAFT has no results: management-first, full width.
    return (
      <div className="space-y-5">
        {header}
        {statusArea}
        <Surface className="p-5 sm:p-6">
          <h2 className="mb-5 font-heading text-xl font-bold">
            {t("Configuration")}
          </h2>
          <PublicationSummary configuration={publication.configuration} />
          {publication.configuration.audience === "PUBLIC" &&
          publication.privateLocator !== null ? (
            <div className="mt-5">
              <PrivatePublicationLocator
                key={`${publication.publicationId}:${publication.revision}`}
                locator={publication.privateLocator}
              />
            </div>
          ) : null}
        </Surface>
        {!editBase ? (
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={blocked || readFailure !== null}
              onClick={() => {
                setEditBase(publication);
                setMutationFailure(null);
              }}
              variant="secondary"
            >
              {t("Edit DRAFT")}
            </Button>
            <Button
              disabled={
                blocked ||
                readFailure !== null ||
                publication.configuration.monitoringChoice === "UNCONFIGURED"
              }
              onClick={() => setConfirmation("open")}
            >
              {t("Review and OPEN")}
            </Button>
          </div>
        ) : null}
        {publication.configuration.monitoringChoice === "UNCONFIGURED" ? (
          <Alert
            title={t("Choose a monitoring option first")}
            variant="warning"
          >
            {t("Edit this DRAFT and choose enabled or disabled before OPEN.")}
          </Alert>
        ) : null}
        {editBase ? (
          <section className="space-y-4">
            <h2 className="font-heading text-2xl font-bold">
              {t("Edit DRAFT configuration")}
            </h2>
            <p className="text-sm text-foreground-muted">
              {t(
                "Your edits are based on revision {base}; the current revision is {current}. The quiz version stays the same.",
                { base: editBase.revision, current: publication.revision },
              )}
            </p>
            {editingConflict ? (
              <Alert
                title={t("Review the latest configuration")}
                variant="warning"
              >
                {t(
                  "Your entered values remain below. They will not be replayed against a newer revision.",
                )}{" "}
                {publication.state === "DRAFT" ? (
                  <Button
                    className="mt-3"
                    disabled={blocked}
                    onClick={() => {
                      setEditBase(publication);
                      setEditReset((value) => value + 1);
                      setMutationFailure(null);
                    }}
                    variant="secondary"
                  >
                    {t("Use the latest configuration (discard my edits)")}
                  </Button>
                ) : (
                  t("The Publication is no longer editable.")
                )}
              </Alert>
            ) : null}
            <PublicationConfigurationForm
              disabled={blocked || editingConflict || readFailure !== null}
              initial={editBase.configuration}
              isPending={busy}
              key={editReset}
              mode="edit"
              onDirtyChange={setEditDirty}
              onSubmit={(configuration) =>
                void send({
                  kind: "update",
                  input: { expectedRevision: editBase.revision, configuration },
                })
              }
            />
            <Button
              disabled={blocked}
              onClick={() => {
                // Cancel editing discards unsaved values; confirm first.
                // Cancelling keeps the form open with values intact.
                if (
                  editDirty &&
                  !window.confirm(
                    localize(
                      "This form has unsaved changes. Discard them and stop editing?",
                    ),
                  )
                )
                  return;
                setEditBase(null);
              }}
              variant="secondary"
            >
              {t("Cancel editing")}
            </Button>
          </section>
        ) : null}
        {confirmation ? confirmationDialog : null}
      </div>
    );
  }

  // OPEN/CLOSED: results-first workspace — dominant main column with the
  // authorized results, compact configuration/actions sidebar on the right.
  return (
    <div className="space-y-5">
      {header}
      {statusArea}
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
        <div className="min-w-0 space-y-5">
          {publication.state === "CLOSED" ? (
            <Alert title={t("Assessment closed")} variant="success">
              {t(
                "Students who submitted can now review their answers according to the review policy.",
              )}
            </Alert>
          ) : null}
          {extension ? (
            <section className="space-y-4">
              <h2 className="font-heading text-2xl font-bold">
                {t("Extend participation schedule")}
              </h2>
              {extension.revision !== publication.revision ? (
                <Alert title={t("Schedule was changed")} variant="warning">
                  {t(
                    "Your extension values remain below and will not be applied to a newer revision.",
                  )}
                  <Button
                    className="mt-3"
                    disabled={blocked}
                    onClick={() => setExtension(publication)}
                    variant="secondary"
                  >
                    {t("Use the latest schedule (discard my extension)")}
                  </Button>
                </Alert>
              ) : null}
              <ScheduleExtension
                disabled={
                  blocked ||
                  readFailure !== null ||
                  extension.revision !== publication.revision
                }
                isPending={busy}
                key={extension.revision}
                onDirtyChange={setExtensionDirty}
                onSubmit={(input) => void send({ kind: "extend", input })}
                publication={extension}
              />
            </section>
          ) : null}
          {resultsClient !== null ? (
            <PublicationResultsSection
              client={resultsClient}
              publicationId={publication.publicationId}
            />
          ) : (
            <Alert title={t("Results unavailable")} variant="warning">
              {t("Please sign in again to view results.")}
            </Alert>
          )}
        </div>
        <aside
          aria-label={t("Configuration and actions")}
          className="min-w-0 space-y-4 lg:sticky lg:top-6"
        >
          <ConfigurationSummaryCard publication={publication} />
          <Surface className="space-y-3 p-5">
            <h2 className="font-heading text-lg font-bold">{t("Actions")}</h2>
            {publication.state === "OPEN" ? (
              <>
                <Button
                  className="w-full"
                  disabled={blocked || readFailure !== null}
                  onClick={() => {
                    if (extension === null) {
                      setExtension(publication);
                      return;
                    }
                    // The Hide toggle discards unsaved extension values;
                    // confirm first; cancelling keeps the form intact.
                    if (
                      extensionDirty &&
                      !window.confirm(
                        localize(
                          "The schedule extension has unsaved values. Hide it and discard them?",
                        ),
                      )
                    )
                      return;
                    setExtension(null);
                  }}
                  variant="secondary"
                >
                  {extension
                    ? t("Hide schedule extension")
                    : t("Extend participation schedule")}
                </Button>
                <Link
                  className={`${OUTLINE_LINK_CLASSES} w-full`}
                  href={`/app/assessments/${publication.publicationId}/closure`}
                >
                  {t("Manage delayed closure")}
                </Link>
                <Button
                  className="w-full"
                  disabled={blocked || readFailure !== null}
                  onClick={() => setConfirmation("close")}
                  variant="danger"
                >
                  {t("Review and CLOSE")}
                </Button>
              </>
            ) : (
              <Link
                className={`${OUTLINE_LINK_CLASSES} w-full`}
                href={`/app/assessments/${publication.publicationId}/closure`}
              >
                {t("Manage delayed closure")}
              </Link>
            )}
            <p className="break-all pt-1 font-mono text-[0.65rem] leading-4 text-foreground-muted">
              {publication.publicationId}
            </p>
          </Surface>
        </aside>
      </div>
      {confirmation ? confirmationDialog : null}
    </div>
  );
}
