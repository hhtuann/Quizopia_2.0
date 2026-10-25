"use client";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert } from "../../../components/ui/alert";
import { Button, OUTLINE_LINK_CLASSES } from "../../../components/ui/button";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { StateBadge } from "../../../components/ui/state-badge";
import { Surface } from "../../../components/ui/surface";
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

export function PublicationDetailScreen({
  client,
  publicationId,
}: {
  readonly client: PublicationApiClient;
  readonly publicationId: string;
}) {
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
  const [extension, setExtension] = useState<OwnerView | null>(null);

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
          ? "CLOSED confirmed by the server."
          : "Publication change confirmed by the server.",
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
          ? "The server throttled direct CLOSE. Reading authoritative state before an explicit same-key retry."
          : retainCommand
            ? "Outcome uncertain. Reading authoritative state before another action."
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
            "Authoritative details confirm the current state. No mutation was replayed.",
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
        "Authoritative details confirm the current state. No mutation was replayed.",
      );
    }
  }

  if (publication === null)
    return (
      <Surface className="p-6">
        <h1 className="font-heading text-3xl font-bold">Publication details</h1>
        {loading ? (
          <div className="mt-5 flex items-center gap-3" role="status">
            <LoadingIndicator label="Loading Publication" />
            Loading authoritative details…
          </div>
        ) : (
          <>
            <Alert
              className="mt-5"
              title="Publication could not be loaded"
              variant="warning"
            >
              {readFailure
                ? publicationFailureMessage(readFailure)
                : "Publication metadata is unavailable."}
            </Alert>
            <Button
              className="mt-4"
              onClick={() => {
                setLoading(true);
                void refresh();
              }}
              variant="secondary"
            >
              Retry Publication details
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
  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-primary">
            Assessment Publication
          </p>
          <h1 className="mt-1 break-words font-heading text-3xl font-bold">
            {publication.configuration.title}
          </h1>
          <p className="mt-2 flex flex-wrap items-center gap-3 text-sm">
            <StateBadge variant={publicationStateVariants[publication.state]}>
              {publication.state}
            </StateBadge>
            <span className="text-foreground-muted">
              Revision {publication.revision}
            </span>
          </p>
          <div className="mt-2">
            <RealtimeStatusInline status={realtimeStatus} />
          </div>
        </div>
        <Button
          aria-label={loading ? "Refreshing authoritative details" : undefined}
          disabled={busy}
          isLoading={loading}
          loadingLabel="Refreshing authoritative details"
          onClick={() => void reconcile()}
          variant="secondary"
        >
          Refresh status
        </Button>
      </header>
      <p className="break-all font-mono text-xs text-foreground-muted">
        Publication ID: {publication.publicationId}
      </p>
      <p className="text-sm text-foreground-muted">
        OPEN finalizes the snapshot. Participation start/end and individual
        Attempt deadlines are separate. Window end never means CLOSED.
      </p>
      <p
        aria-live="polite"
        className="text-sm font-medium text-foreground-secondary"
        role="status"
      >
        {status}
      </p>
      {readFailure ? (
        <Alert title="Current state could not be refreshed" variant="warning">
          {publicationFailureMessage(readFailure)} Previously verified metadata
          remains visible; refresh before changing it.
        </Alert>
      ) : null}
      {mutationFailure ? (
        <Alert
          title={
            pending &&
            mutationFailure.kind === "api" &&
            mutationFailure.status === 429
              ? "Mutation identity retained"
              : pending
                ? "Mutation outcome is uncertain"
                : "Operation was not confirmed"
          }
          variant="warning"
        >
          {publicationFailureMessage(mutationFailure)}
        </Alert>
      ) : null}
      {pending ? (
        <Surface className="space-y-3 border-warning p-5">
          <h2 className="font-semibold">Reconcile before continuing</h2>
          <p className="text-sm leading-6">
            This operation may have committed. Refresh reads current authority;
            it never repeats a mutation. After a successful read, an explicit
            retry sends only the original command and revision
            {pending.kind === "close" ? " with the same idempotency key" : ""}.
            No newer revision is substituted.
          </p>
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={busy || loading}
              onClick={() => void reconcile()}
              variant="secondary"
            >
              Reconcile state
            </Button>
            <Button
              aria-label={busy ? "Retrying original command" : undefined}
              disabled={!reconciled || loading || readFailure !== null}
              isLoading={busy}
              loadingLabel="Retrying original command"
              onClick={() => void send(pending)}
            >
              Retry original command
            </Button>
          </div>
        </Surface>
      ) : null}
      <Surface className="p-5 sm:p-6">
        <h2 className="mb-5 font-heading text-xl font-bold">
          Authoritative configuration
        </h2>
        <PublicationSummary configuration={publication.configuration} />
        {publication.configuration.audience === "PUBLIC" &&
        publication.privateLocator !== null ? (
          <PrivatePublicationLocator
            key={`${publication.publicationId}:${publication.revision}`}
            locator={publication.privateLocator}
          />
        ) : null}
      </Surface>
      {publication.state === "DRAFT" && !editBase ? (
        <div className="flex flex-wrap gap-3">
          <Button
            disabled={blocked || readFailure !== null}
            onClick={() => {
              setEditBase(publication);
              setMutationFailure(null);
            }}
            variant="secondary"
          >
            Edit DRAFT
          </Button>
          <Button
            disabled={
              blocked ||
              readFailure !== null ||
              publication.configuration.monitoringChoice === "UNCONFIGURED"
            }
            onClick={() => setConfirmation("open")}
          >
            Review and OPEN
          </Button>
        </div>
      ) : null}
      {publication.state === "DRAFT" &&
      publication.configuration.monitoringChoice === "UNCONFIGURED" ? (
        <Alert title="Explicit monitoring choice required" variant="warning">
          Edit this CLASS DRAFT and choose enabled or disabled before OPEN.
        </Alert>
      ) : null}
      {editBase ? (
        <section className="space-y-4">
          <h2 className="font-heading text-2xl font-bold">
            Edit DRAFT configuration
          </h2>
          <p className="text-sm text-foreground-muted">
            Your form is based on revision {editBase.revision}; current server
            revision is {publication.revision}. Source version remains pinned.
          </p>
          {editingConflict ? (
            <Alert
              title="Review the current server configuration"
              variant="warning"
            >
              Your entered values remain below. They will not be replayed
              against a newer revision.{" "}
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
                  Use server configuration (discard local edits)
                </Button>
              ) : (
                "The Publication is no longer editable."
              )}
            </Alert>
          ) : null}
          <PublicationConfigurationForm
            disabled={blocked || editingConflict || readFailure !== null}
            initial={editBase.configuration}
            isPending={busy}
            key={editReset}
            mode="edit"
            onSubmit={(configuration) =>
              void send({
                kind: "update",
                input: { expectedRevision: editBase.revision, configuration },
              })
            }
          />
          <Button
            disabled={blocked}
            onClick={() => setEditBase(null)}
            variant="secondary"
          >
            Cancel editing
          </Button>
        </section>
      ) : null}
      {publication.state === "OPEN" ? (
        <section className="space-y-4">
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={blocked || readFailure !== null}
              onClick={() =>
                setExtension((value) => (value ? null : publication))
              }
              variant="secondary"
            >
              {extension
                ? "Hide schedule extension"
                : "Extend participation schedule"}
            </Button>
            <Button
              disabled={blocked || readFailure !== null}
              onClick={() => setConfirmation("close")}
              variant="danger"
            >
              Review and CLOSE
            </Button>
          </div>
          <div className="flex flex-wrap gap-3">
            <Link
              className={OUTLINE_LINK_CLASSES}
              href={`/app/assessments/${publication.publicationId}/closure`}
            >
              Manage delayed closure
            </Link>
            <Link
              className={OUTLINE_LINK_CLASSES}
              href={`/app/assessments/${publication.publicationId}/results`}
            >
              View attempt results
            </Link>
          </div>
          {extension ? (
            <div className="space-y-4">
              {extension.revision !== publication.revision ? (
                <Alert title="Schedule revision changed" variant="warning">
                  Your extension values remain below and will not be applied to
                  a newer revision.
                  <Button
                    className="mt-3"
                    disabled={blocked}
                    onClick={() => setExtension(publication)}
                    variant="secondary"
                  >
                    Use server schedule (discard local extension)
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
                onSubmit={(input) => void send({ kind: "extend", input })}
                publication={extension}
              />
            </div>
          ) : null}
        </section>
      ) : null}
      {publication.state === "CLOSED" ? (
        <>
          <Alert title="Publication CLOSED" variant="success">
            Durable closure is confirmed. Eligible submitted Students may review
            according to backend policy. Student results and answer-review UI
            are outside this Teacher workflow.
          </Alert>
          <div className="flex flex-wrap gap-3">
            <Link
              className={OUTLINE_LINK_CLASSES}
              href={`/app/assessments/${publication.publicationId}/results`}
            >
              View attempt results
            </Link>
          </div>
        </>
      ) : null}
      {confirmation ? (
        <PublicationConfirmation
          confirmLabel={
            confirmation === "open" ? "OPEN Publication" : "CLOSE Publication"
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
              ? "Review and OPEN Publication"
              : "Confirm direct CLOSE"
          }
        >
          <PublicationSummary configuration={publication.configuration} />
          {confirmation === "open" ? (
            <p>
              OPEN finalizes an immutable, self-contained assessment snapshot.
              Source, audience and monitoring freeze; OPEN does not mean the
              participation window has started.
            </p>
          ) : (
            <p>
              CLOSE is separate from participation-window end. Active Attempts
              may prevent closure. There is no force-close here. Durable CLOSED
              controls eligible Student answer review; this command does not ask
              the browser to submit or abandon anyone’s work.
            </p>
          )}
        </PublicationConfirmation>
      ) : null}
    </div>
  );
}
