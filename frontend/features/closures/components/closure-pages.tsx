"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button, OUTLINE_LINK_CLASSES } from "../../../components/ui/button";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { StateBadge } from "../../../components/ui/state-badge";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
import {
  assessmentFailureMessage,
  isUncertainAssessmentFailure,
} from "../../assessments/api/assessment-foundation";
import type { PublicationApiClient } from "../../assessments/api/publication-api-client";
import { publicationFailureMessage } from "../../assessments/api/publication-api-client";
import type {
  ClosureCompletion,
  ClosureView,
  OwnerView,
  TeacherCommand,
} from "../../assessments/api/publication-contract.generated";
import { canonicalUuid } from "../../assessments/model/publication";
import { newIdempotencyKey } from "../../assessments/model/publication";
import { usePublicationClient } from "../../assessments/hooks/use-publication-client";
import { formatUtc } from "../../assessments/utils/utc-time";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import type { ClosureApiClient } from "../api/closure-api-client";
import { useClosureClient } from "../hooks/use-closure-client";
import {
  canCancelClosure,
  canCompleteClosure,
  closureStateDescriptions,
  closureStateLabels,
  closureStateVariants,
  isLiveClosureState,
} from "../model/closure";
import { TypedConfirmation } from "./typed-confirmation";

type MutationKind = "request" | "cancel" | "complete";

interface Command {
  readonly kind: MutationKind;
  readonly input: TeacherCommand;
  readonly key: string;
}

function isConflict(
  error: Parameters<typeof assessmentFailureMessage>[0],
): boolean {
  return error.kind === "api" && error.status === 409;
}

function retainsCommandIdentityAfterFailure(
  command: Command,
  error: Parameters<typeof assessmentFailureMessage>[0],
): boolean {
  return (
    error.kind === "api" &&
    (error.status === 429 ||
      (command.kind === "complete" &&
        error.status === 409 &&
        error.code === "CLOSURE_NOT_READY"))
  );
}

export function ClosureManagementPage({
  publicationId,
}: {
  readonly publicationId: string;
}) {
  return (
    <AuthenticatedBoundary>
      <TeacherClosurePage publicationId={publicationId} />
    </AuthenticatedBoundary>
  );
}

function TeacherClosurePage({
  publicationId,
}: {
  readonly publicationId: string;
}) {
  const { user, activeWorkspace, switchWorkspace, getSessionGeneration } =
    useAuth();
  const publicationClient = usePublicationClient();
  const closureClient = useClosureClient();
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
        <p className="text-sm font-semibold text-primary">
          Teaching · Closure workflow
        </p>
      </div>
      {!user?.roles.includes("TEACHER") ? (
        <Alert title="Teacher access required" variant="warning">
          Current Teacher authority and Publication ownership are required.
          Workspace selection does not grant a role.{" "}
          <Link className="font-semibold underline" href="/app">
            Return to application
          </Link>
          .
        </Alert>
      ) : activeWorkspace !== "TEACHING" ? (
        <Surface className="p-6">
          <h1 className="text-2xl font-bold">Open the Teaching workspace</h1>
          <p className="mt-3 text-foreground-secondary">
            The closure workflow uses your existing Teacher role. The server
            checks ownership and admission for every operation.
          </p>
          <Button className="mt-5" onClick={() => switchWorkspace("TEACHING")}>
            Switch to Teaching workspace
          </Button>
        </Surface>
      ) : publicationClient === null || closureClient === null ? (
        <Alert title="Closure access unavailable" variant="warning">
          Your authenticated request service is unavailable. Sign in again
          before continuing.
        </Alert>
      ) : (
        <ClosureManagementScreen
          key={scope}
          publicationClient={publicationClient}
          closureClient={closureClient}
          publicationId={publicationId}
        />
      )}
    </ApplicationShell>
  );
}

export function ClosureManagementScreen({
  publicationClient,
  closureClient,
  publicationId,
}: {
  readonly publicationClient: PublicationApiClient;
  readonly closureClient: ClosureApiClient;
  readonly publicationId: string;
}) {
  const router = useRouter();
  const lifetime = useRef<AbortController | null>(null);
  const readController = useRef<AbortController | null>(null);
  const readSequence = useRef(0);
  const inFlight = useRef(false);
  const [publication, setPublication] = useState<OwnerView | null>(null);
  const [loading, setLoading] = useState(true);
  const [readFailure, setReadFailure] = useState<string | null>(null);
  const [mutationFailureText, setMutationFailureText] = useState<string | null>(
    null,
  );
  const [pending, setPending] = useState<Command | null>(null);
  const [reconciled, setReconciled] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [created, setCreated] = useState<ClosureView | null>(null);
  const [createdIsNew, setCreatedIsNew] = useState(false);
  const [knownId, setKnownId] = useState("");
  const [knownInvalid, setKnownInvalid] = useState(false);

  const refresh = useCallback((): Promise<OwnerView | null> => {
    const sequence = ++readSequence.current;
    readController.current?.abort();
    const controller = new AbortController();
    readController.current = controller;
    return publicationClient
      .get(publicationId, controller.signal)
      .then((result) => {
        if (
          controller.signal.aborted ||
          lifetime.current?.signal.aborted ||
          sequence !== readSequence.current
        )
          return null;
        setLoading(false);
        if (!result.ok) {
          setReadFailure(publicationFailureMessage(result.error));
          return null;
        }
        setReadFailure(null);
        setPublication(result.value);
        return result.value;
      });
  }, [publicationClient, publicationId]);

  useEffect(() => {
    const scope = new AbortController();
    lifetime.current = scope;
    void refresh();
    return () => {
      scope.abort();
      readController.current?.abort();
    };
  }, [refresh]);

  async function sendRequest(command: Command) {
    if (inFlight.current || lifetime.current?.signal.aborted) return;
    inFlight.current = true;
    setBusy(true);
    setMutationFailureText(null);
    setStatus("Sending reviewed closure request…");
    const signal = lifetime.current?.signal;
    const result = await closureClient.request(
      publicationId,
      command.input,
      command.key,
      signal,
    );
    if (signal?.aborted) return;
    if (result.ok) {
      setPending(null);
      setCreated(result.value.view);
      setCreatedIsNew(result.value.created);
      setStatus(
        result.value.created
          ? "Closure request created. The Publication is NOT closed; notices prepare independently."
          : "The server returned the existing request for this operation identity.",
      );
      setLoading(true);
      await refresh();
    } else {
      const uncertain = isUncertainAssessmentFailure(result.error);
      const conflict = isConflict(result.error);
      const retainCommand =
        uncertain || retainsCommandIdentityAfterFailure(command, result.error);
      setMutationFailureText(assessmentFailureMessage(result.error));
      setPending(retainCommand ? command : null);
      setReconciled(false);
      setStatus(
        result.error.kind === "api" && result.error.status === 429
          ? "The server throttled this operation. Reading authoritative state before an explicit same-key retry."
          : uncertain
            ? "Outcome uncertain. Reading authoritative state before another action."
            : conflict
              ? "The command conflicted with server state. Reading authoritative details before another action."
              : "Closure request was not created.",
      );
      if (
        (retainCommand || conflict) &&
        !(
          result.error.kind === "unavailable" &&
          result.error.reason === "refresh"
        )
      ) {
        setLoading(true);
        const current = await refresh();
        if (signal?.aborted) return;
        if (current) setReconciled(true);
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
  }

  function openKnownRequest(event: React.FormEvent) {
    event.preventDefault();
    if (!canonicalUuid.safeParse(knownId).success) {
      setKnownInvalid(true);
      return;
    }
    router.push(`/app/assessments/${publicationId}/closure/${knownId}`);
  }

  if (publication === null)
    return (
      <Surface className="mx-auto max-w-3xl p-6">
        <h1 className="font-heading text-3xl font-bold">Closure workflow</h1>
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
              {readFailure ?? "Publication metadata is unavailable."} The
              closure workflow requires an owner-authorized Publication.
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
  return (
    <div className="mx-auto max-w-4xl space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-primary">
            Delayed closure workflow
          </p>
          <h1 className="mt-1 break-words font-heading text-3xl font-bold">
            {publication.configuration.title}
          </h1>
          <p className="mt-2 text-sm">
            <span className="rounded-full bg-primary/10 px-3 py-1 font-semibold text-primary">
              {publication.state}
            </span>
            <span className="ml-3 text-foreground-muted">
              Revision {publication.revision}
            </span>
          </p>
        </div>
        <Button
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
      <p
        aria-live="polite"
        className="text-sm font-medium text-foreground-secondary"
        role="status"
      >
        {status}
      </p>
      {readFailure ? (
        <Alert title="Current state could not be refreshed" variant="warning">
          {readFailure} Previously verified metadata remains visible; refresh
          before changing anything.
        </Alert>
      ) : null}
      {mutationFailureText ? (
        <Alert
          title={
            pending ? "Request outcome is uncertain" : "Request was not created"
          }
          variant="warning"
        >
          {mutationFailureText}
        </Alert>
      ) : null}
      {pending ? (
        <Surface className="space-y-3 border-warning p-5">
          <h2 className="font-semibold">Reconcile before continuing</h2>
          <p className="text-sm leading-6">
            The closure request may have committed. Refresh reads current
            authority; it never repeats a mutation. An explicit retry re-sends
            only the original revision with the same idempotency key; a matching
            replay returns the existing request instead of creating another.
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
              disabled={!reconciled || loading || readFailure !== null}
              isLoading={busy}
              loadingLabel="Retrying original request"
              onClick={() => void sendRequest(pending)}
            >
              Retry original request
            </Button>
          </div>
        </Surface>
      ) : null}
      {created ? (
        <Surface className="space-y-3 p-5">
          <h2 className="font-heading text-xl font-bold">
            {createdIsNew
              ? "Closure request created"
              : "Existing closure request"}
          </h2>
          <p className="text-sm leading-6 text-foreground-secondary">
            A successful request records durable intent and starts recipient
            notice preparation. It does <strong>not</strong> close the
            Publication; track progress on the request page.
          </p>
          <dl className="grid gap-3 sm:grid-cols-2">
            <div>
              <dt className="text-xs font-medium text-foreground-muted">
                Closure request ID
              </dt>
              <dd className="break-all font-mono text-xs">
                {created.closureRequestId}
              </dd>
            </div>
            <div>
              <dt className="text-xs font-medium text-foreground-muted">
                State
              </dt>
              <dd>
                <StateBadge variant={closureStateVariants[created.state]}>
                  {closureStateLabels[created.state]}
                </StateBadge>
              </dd>
            </div>
          </dl>
          <Link
            className={OUTLINE_LINK_CLASSES}
            href={`/app/assessments/${publicationId}/closure/${created.closureRequestId}`}
          >
            Open closure request
          </Link>
        </Surface>
      ) : null}
      <Surface className="p-5 sm:p-6">
        <h2 className="mb-4 font-heading text-xl font-bold">
          Authoritative window
        </h2>
        <dl className="grid gap-4 sm:grid-cols-2">
          <div>
            <dt className="text-sm font-medium text-foreground-muted">
              Participation end (server authority)
            </dt>
            <dd className="mt-1 text-sm">
              {formatUtc(publication.configuration.endAt)}
            </dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-foreground-muted">
              Unlimited attempt duration
            </dt>
            <dd className="mt-1 text-sm">
              {publication.configuration.durationMinutes === 0
                ? "Yes — captured by delayed closure"
                : "No — bounded attempts finalize by their own deadlines"}
            </dd>
          </div>
        </dl>
        <p className="mt-4 text-sm leading-6 text-foreground-secondary">
          Delayed closure may be requested only after the server&apos;s
          authoritative clock passes the participation end. This screen never
          decides eligibility from browser time; the server rejects a request
          that is too early.
        </p>
      </Surface>
      {publication.state === "OPEN" ? (
        <Surface className="space-y-4 p-5 sm:p-6">
          <h2 className="font-heading text-xl font-bold">Request closure</h2>
          <p className="text-sm leading-6 text-foreground-secondary">
            A closure request captures every currently ACTIVE unlimited Attempt
            and queues durable warning preparation for each recipient. The
            backend publishes each notice asynchronously and confirms it only
            after proving recipient access; only a confirmed recipient gets a
            48-hour cutoff before eligible saved answers may be finalized. An
            unverifiable recipient may remain unconfirmed indefinitely. Zero
            captured recipients is valid. While a request is live, schedule
            changes and new attempts are blocked.
          </p>
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={blocked || readFailure !== null}
              onClick={() => setConfirming(true)}
            >
              Review and REQUEST closure
            </Button>
            <Link
              className={OUTLINE_LINK_CLASSES}
              href={`/app/assessments/${publicationId}`}
            >
              Use direct CLOSE on Publication details
            </Link>
          </div>
        </Surface>
      ) : (
        <Alert
          title="Closure requests require an OPEN Publication"
          variant="info"
        >
          This Publication is {publication.state}. Direct CLOSE and its
          reconciliation remain on the{" "}
          <Link
            className="font-semibold underline"
            href={`/app/assessments/${publicationId}`}
          >
            Publication details
          </Link>{" "}
          screen; a delayed-closure workflow applies only while OPEN.
        </Alert>
      )}
      <Surface className="p-5 sm:p-6">
        <h2 className="font-heading text-xl font-bold">
          Open a known closure request
        </h2>
        <p className="mt-2 text-sm text-foreground-muted">
          There is no closure-request list API. Enter a known request UUID; the
          server verifies ownership.
        </p>
        <form className="mt-4 space-y-4" noValidate onSubmit={openKnownRequest}>
          <TextField
            error={
              knownInvalid
                ? "Enter a canonical lowercase closure request UUID."
                : undefined
            }
            label="Closure request ID"
            onChange={(event) => setKnownId(event.target.value)}
            required
            value={knownId}
          />
          <Button type="submit" variant="secondary">
            Open closure request
          </Button>
        </form>
      </Surface>
      {confirming ? (
        <TypedConfirmation
          confirmLabel="REQUEST delayed closure"
          destructive={false}
          onCancel={() => setConfirming(false)}
          onConfirm={() => {
            setConfirming(false);
            void sendRequest({
              kind: "request",
              input: { expectedRevision: publication.revision },
              key: newIdempotencyKey(),
            });
          }}
          phrase="REQUEST"
          title="Confirm delayed closure request"
        >
          <p>
            Current authoritative revision:{" "}
            <strong>{publication.revision}</strong>. The request will be sent
            with this revision and one durable operation key.
          </p>
          <p>
            Requesting closure does not close the Publication. Each recipient
            first receives a durable notice; the 48-hour period for a recipient
            starts only when the server confirms their notice is genuinely
            accessible — never at this button click.
          </p>
          <p>
            An ineligible, disabled or unverifiable recipient can block closure
            indefinitely; that accepted limitation has no force-close or
            automatic workaround.
          </p>
        </TypedConfirmation>
      ) : null}
    </div>
  );
}

export function ClosureDetailPage({
  publicationId,
  closureRequestId,
}: {
  readonly publicationId: string;
  readonly closureRequestId: string;
}) {
  return (
    <AuthenticatedBoundary>
      <TeacherClosureDetailPage
        closureRequestId={closureRequestId}
        publicationId={publicationId}
      />
    </AuthenticatedBoundary>
  );
}

function TeacherClosureDetailPage({
  publicationId,
  closureRequestId,
}: {
  readonly publicationId: string;
  readonly closureRequestId: string;
}) {
  const { user, activeWorkspace, switchWorkspace, getSessionGeneration } =
    useAuth();
  const closureClient = useClosureClient();
  const scope = `${user?.id}:${getSessionGeneration()}:${publicationId}:${closureRequestId}`;
  return (
    <ApplicationShell currentSection="assessments">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <Link
          className={OUTLINE_LINK_CLASSES}
          href={`/app/assessments/${publicationId}/closure`}
        >
          Back to closure workflow
        </Link>
        <p className="text-sm font-semibold text-primary">
          Teaching · Closure request
        </p>
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
          <Button className="mt-5" onClick={() => switchWorkspace("TEACHING")}>
            Switch to Teaching workspace
          </Button>
        </Surface>
      ) : closureClient === null ? (
        <Alert title="Closure access unavailable" variant="warning">
          Your authenticated request service is unavailable. Sign in again
          before continuing.
        </Alert>
      ) : (
        <ClosureDetailScreen
          closureClient={closureClient}
          closureRequestId={closureRequestId}
          key={scope}
          publicationId={publicationId}
        />
      )}
    </ApplicationShell>
  );
}

function commandSatisfied(command: Command, view: ClosureView): boolean {
  if (command.kind === "cancel") return view.state === "CANCELLED";
  if (command.kind === "complete") return view.state === "COMPLETED";
  return false;
}

export function ClosureDetailScreen({
  closureClient,
  publicationId,
  closureRequestId,
}: {
  readonly closureClient: ClosureApiClient;
  readonly publicationId: string;
  readonly closureRequestId: string;
}) {
  const lifetime = useRef<AbortController | null>(null);
  const readController = useRef<AbortController | null>(null);
  const readSequence = useRef(0);
  const inFlight = useRef(false);
  const [view, setView] = useState<ClosureView | null>(null);
  const [loading, setLoading] = useState(true);
  const [readFailureText, setReadFailureText] = useState<string | null>(null);
  const [mutationFailureText, setMutationFailureText] = useState<string | null>(
    null,
  );
  const [pending, setPending] = useState<Command | null>(null);
  const [reconciled, setReconciled] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [confirming, setConfirming] = useState<MutationKind | null>(null);
  const [completion, setCompletion] = useState<ClosureCompletion | null>(null);

  const refresh = useCallback((): Promise<ClosureView | null> => {
    const sequence = ++readSequence.current;
    readController.current?.abort();
    const controller = new AbortController();
    readController.current = controller;
    return closureClient
      .get(publicationId, closureRequestId, controller.signal)
      .then((result) => {
        if (
          controller.signal.aborted ||
          lifetime.current?.signal.aborted ||
          sequence !== readSequence.current
        )
          return null;
        setLoading(false);
        if (!result.ok) {
          setReadFailureText(assessmentFailureMessage(result.error));
          return null;
        }
        setReadFailureText(null);
        setView(result.value);
        return result.value;
      });
  }, [closureClient, publicationId, closureRequestId]);

  useEffect(() => {
    const scope = new AbortController();
    lifetime.current = scope;
    void refresh();
    return () => {
      scope.abort();
      readController.current?.abort();
    };
  }, [refresh]);

  async function send(command: Command) {
    if (inFlight.current || lifetime.current?.signal.aborted) return;
    inFlight.current = true;
    setBusy(true);
    setMutationFailureText(null);
    setStatus("Sending reviewed command…");
    const signal = lifetime.current?.signal;
    if (command.kind === "cancel") {
      const result = await closureClient.cancel(
        publicationId,
        closureRequestId,
        command.input,
        command.key,
        signal,
      );
      if (signal?.aborted) return;
      if (result.ok) {
        setView(result.value);
        setPending(null);
        setCompletion(null);
        setStatus("Cancellation confirmed by the server.");
      } else await handleFailure(result.error, command, signal);
    } else {
      const result = await closureClient.complete(
        publicationId,
        closureRequestId,
        command.input,
        command.key,
        signal,
      );
      if (signal?.aborted) return;
      if (result.ok) {
        setCompletion(result.value);
        setPending(null);
        setStatus(
          result.value.closed
            ? "Completion committed the durable CLOSED transition."
            : "Committed bounded progress (closed=false). Inspect status; start another batch with a new key when ready.",
        );
        setLoading(true);
        await refresh();
      } else await handleFailure(result.error, command, signal);
    }
    setBusy(false);
    inFlight.current = false;
  }

  async function handleFailure(
    error: Parameters<typeof assessmentFailureMessage>[0],
    command: Command,
    signal: AbortSignal | undefined,
  ) {
    const uncertain = isUncertainAssessmentFailure(error);
    const conflict = isConflict(error);
    const retainCommand =
      uncertain || retainsCommandIdentityAfterFailure(command, error);
    setMutationFailureText(assessmentFailureMessage(error));
    setPending(retainCommand ? command : null);
    setReconciled(false);
    setStatus(
      error.kind === "api" && error.status === 429
        ? "The server throttled this operation. Reading authoritative status before an explicit same-key retry."
        : uncertain
          ? "Outcome uncertain. Reading authoritative state before another action."
          : conflict
            ? "The command conflicted with server state. Reading authoritative status before another action."
            : "Operation was not confirmed.",
    );
    if (
      (retainCommand || conflict) &&
      !(error.kind === "unavailable" && error.reason === "refresh")
    ) {
      setLoading(true);
      const current = await refresh();
      if (signal?.aborted) return;
      if (current) setReconciled(true);
      if (current && commandSatisfied(command, current)) {
        setPending(null);
        setMutationFailureText(null);
        setStatus(
          "Authoritative status confirms the command's effect. No mutation was replayed.",
        );
      }
    }
  }

  async function reconcile() {
    setLoading(true);
    setReconciled(false);
    const current = await refresh();
    if (current) setReconciled(true);
    if (current && pending && commandSatisfied(pending, current)) {
      setPending(null);
      setMutationFailureText(null);
      setStatus(
        "Authoritative status confirms the command's effect. No mutation was replayed.",
      );
    }
  }

  if (view === null)
    return (
      <Surface className="mx-auto max-w-3xl p-6">
        <h1 className="font-heading text-3xl font-bold">Closure request</h1>
        {loading ? (
          <div className="mt-5 flex items-center gap-3" role="status">
            <LoadingIndicator label="Loading closure request" />
            Loading authoritative status…
          </div>
        ) : (
          <>
            <Alert
              className="mt-5"
              title="Closure request could not be loaded"
              variant="warning"
            >
              {readFailureText ?? "Closure request metadata is unavailable."}
            </Alert>
            <Button
              className="mt-4"
              onClick={() => {
                setLoading(true);
                void refresh();
              }}
              variant="secondary"
            >
              Retry closure request
            </Button>
          </>
        )}
      </Surface>
    );

  const blocked = busy || loading || pending !== null;
  const cancellable = canCancelClosure(view) && readFailureText === null;
  const completable = canCompleteClosure(view) && readFailureText === null;
  return (
    <div className="mx-auto max-w-4xl space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="font-heading text-3xl font-bold">Closure request</h1>
          <div className="mt-3">
            <StateBadge variant={closureStateVariants[view.state]}>
              {closureStateLabels[view.state]}
            </StateBadge>
          </div>
          <p className="mt-2 text-sm text-foreground-muted">
            Publication revision after this request: {view.publicationRevision}
          </p>
        </div>
        <Button
          disabled={busy}
          isLoading={loading}
          loadingLabel="Refreshing authoritative status"
          onClick={() => void reconcile()}
          variant="secondary"
        >
          Refresh status
        </Button>
      </header>
      <p
        aria-live="polite"
        className="text-sm font-medium text-foreground-secondary"
        role="status"
      >
        {status}
      </p>
      {readFailureText ? (
        <Alert title="Status could not be refreshed" variant="warning">
          {readFailureText} Previously verified status remains visible; refresh
          before issuing commands.
        </Alert>
      ) : null}
      {mutationFailureText ? (
        <Alert
          title={
            pending
              ? "Command outcome is uncertain"
              : "Operation was not confirmed"
          }
          variant="warning"
        >
          {mutationFailureText}
        </Alert>
      ) : null}
      {pending ? (
        <Surface className="space-y-3 border-warning p-5">
          <h2 className="font-semibold">Reconcile before continuing</h2>
          <p className="text-sm leading-6">
            This command may have committed. Refresh reads current authority; it
            never repeats a mutation. An explicit retry re-sends only the
            original revision with the same idempotency key, preserving the
            durable command identity.
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
              disabled={!reconciled || loading || readFailureText !== null}
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
          Durable request record
        </h2>
        <dl className="grid gap-4 sm:grid-cols-2">
          <div className="sm:col-span-2">
            <dt className="text-xs font-medium text-foreground-muted">
              Closure request ID (exact)
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {view.closureRequestId}
            </dd>
          </div>
          <div className="sm:col-span-2">
            <dt className="text-xs font-medium text-foreground-muted">
              Publication ID
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {view.publicationId}
            </dd>
          </div>
          <div className="sm:col-span-2">
            <dt className="text-xs font-medium text-foreground-muted">
              Requesting Teacher identity (as returned by the server)
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {view.requestingTeacherId}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              Requested at
            </dt>
            <dd className="mt-1 text-sm">{formatUtc(view.requestedAt)}</dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              Finalization started at
            </dt>
            <dd className="mt-1 text-sm">
              {view.finalizationStartedAt === null
                ? "Not started"
                : formatUtc(view.finalizationStartedAt)}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              Resolved at
            </dt>
            <dd className="mt-1 text-sm">
              {view.resolvedAt === null
                ? "Not resolved"
                : formatUtc(view.resolvedAt)}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              Authoritative Publication revision
            </dt>
            <dd className="mt-1 text-sm">{view.publicationRevision}</dd>
          </div>
        </dl>
        <p className="mt-5 text-sm leading-6 text-foreground-secondary">
          {closureStateDescriptions[view.state]}
        </p>
        <p className="mt-3 text-sm leading-6 text-foreground-muted">
          Recipient counts, per-recipient cutoffs and completion percentages are
          not disclosed by the server for this workflow; none are shown.
        </p>
      </Surface>
      {completion ? (
        <Alert
          title={
            completion.closed
              ? "Publication CLOSED"
              : "Bounded progress committed"
          }
          variant={completion.closed ? "success" : "info"}
        >
          {completion.closed
            ? "The server confirmed the durable CLOSED transition for this Publication."
            : "This operation key committed its bounded batch (closed=false). Its acknowledgment is immutable; use the request status and a new deliberate command to continue."}
        </Alert>
      ) : null}
      {isLiveClosureState(view.state) ? (
        <Surface className="space-y-4 p-5 sm:p-6">
          <h2 className="font-heading text-xl font-bold">
            Manage this request
          </h2>
          <p className="text-sm leading-6 text-foreground-secondary">
            COMPLETE reconciles overdue bounded work and processes due confirmed
            recipients in bounded batches; only the owning Teacher&apos;s
            command can commit the final CLOSED transition. CANCEL is possible
            only before the first closure-driven finalization.
          </p>
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={blocked || !completable}
              onClick={() => setConfirming("complete")}
            >
              Review and COMPLETE closure
            </Button>
            <Button
              disabled={blocked || !cancellable}
              onClick={() => setConfirming("cancel")}
              variant="danger"
            >
              Review and CANCEL request
            </Button>
          </div>
          {!cancellable && view.state === "PENDING" ? (
            <p className="text-sm text-foreground-muted">
              Cancellation is unavailable because finalization has started.
            </p>
          ) : null}
          {view.state === "FINALIZING" ? (
            <p className="text-sm text-foreground-muted">
              The request is FINALIZING; cancellation can no longer win. Use
              COMPLETE to continue bounded batches.
            </p>
          ) : null}
        </Surface>
      ) : (
        <Alert title="This request is resolved" variant="info">
          Resolved workflow states never return to live states. A replacement,
          if still needed, is a new request with fresh notices and fresh 48-hour
          periods.
        </Alert>
      )}
      {confirming !== null ? (
        <TypedConfirmation
          confirmLabel={
            confirming === "cancel"
              ? "CANCEL closure request"
              : "COMPLETE closure"
          }
          destructive={confirming === "cancel"}
          onCancel={() => setConfirming(null)}
          onConfirm={() => {
            const action = confirming;
            setConfirming(null);
            void send({
              kind: action,
              input: { expectedRevision: view.publicationRevision },
              key: newIdempotencyKey(),
            });
          }}
          phrase={confirming === "cancel" ? "CANCEL" : "COMPLETE"}
          title={
            confirming === "cancel"
              ? "Confirm cancellation"
              : "Confirm completion command"
          }
        >
          <p>
            Command revision: <strong>{view.publicationRevision}</strong>. The
            command is sent with this authoritative revision and one durable
            operation key.
          </p>
          {confirming === "cancel" ? (
            <p>
              Cancellation resolves this request while preserving published
              notices to recipients as non-actionable history. A later
              replacement is a new request with new notices and fresh 48-hour
              periods.
            </p>
          ) : (
            <p>
              Completion processes eligible due work in bounded batches. If
              confirmed notices or cutoffs are still outstanding, the server
              answers CLOSURE_NOT_READY without undoing committed progress. The
              Publication closes only when every Attempt is terminal and every
              captured member is resolved.
            </p>
          )}
        </TypedConfirmation>
      ) : null}
    </div>
  );
}
