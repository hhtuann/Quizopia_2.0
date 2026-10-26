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
import { useI18n } from "../../../lib/i18n/provider";
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
import { publicationStateVariants } from "../../assessments/model/publication";
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
  const { t } = useI18n();
  const publicationClient = usePublicationClient();
  const closureClient = useClosureClient();
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
            "Current Teacher authority and Publication ownership are required. Workspace selection does not grant a role.",
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
              "The closure workflow uses your existing Teacher role. The server checks ownership and admission for every operation.",
            )}
          </p>
          <Button className="mt-5" onClick={() => switchWorkspace("TEACHING")}>
            {t("Switch to Teaching workspace")}
          </Button>
        </Surface>
      ) : publicationClient === null || closureClient === null ? (
        <Alert title={t("Closure access unavailable")} variant="warning">
          {t(
            "Your authenticated request service is unavailable. Sign in again before continuing.",
          )}
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
  const { t, localize } = useI18n();
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
      <Surface className="p-6">
        <h1 className="font-heading text-3xl font-bold">
          {t("Closure workflow")}
        </h1>
        {loading ? (
          <div className="mt-5 flex items-center gap-3" role="status">
            <LoadingIndicator label={t("Loading Publication")} />
            {t("Loading authoritative details…")}
          </div>
        ) : (
          <>
            <Alert
              className="mt-5"
              title={t("Publication could not be loaded")}
              variant="warning"
            >
              {readFailure
                ? localize(readFailure)
                : t("Publication metadata is unavailable.")}{" "}
              {t(
                "The closure workflow requires an owner-authorized Publication.",
              )}
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
  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-primary">
            {t("Delayed closure workflow")}
          </p>
          <h1 className="mt-1 break-words font-heading text-3xl font-bold">
            {publication.configuration.title}
          </h1>
          <p className="mt-2 flex flex-wrap items-center gap-3 text-sm">
            {/* Canonical API state enum rendered verbatim by policy. */}
            <StateBadge variant={publicationStateVariants[publication.state]}>
              {publication.state}
            </StateBadge>
            <span className="text-foreground-muted">
              {t("Revision {revision}", { revision: publication.revision })}
            </span>
          </p>
        </div>
        <Button
          disabled={busy}
          isLoading={loading}
          loadingLabel={t("Refreshing authoritative details")}
          onClick={() => void reconcile()}
          variant="secondary"
        >
          {t("Refresh status")}
        </Button>
      </header>
      <p className="break-all font-mono text-xs text-foreground-muted">
        {t("Publication ID: {id}", { id: publication.publicationId })}
      </p>
      <p
        aria-live="polite"
        className="text-sm font-medium text-foreground-secondary"
        role="status"
      >
        {localize(status)}
      </p>
      {readFailure ? (
        <Alert
          title={t("Current state could not be refreshed")}
          variant="warning"
        >
          {localize(readFailure)}{" "}
          {t(
            "Previously verified metadata remains visible; refresh before changing it.",
          )}
        </Alert>
      ) : null}
      {mutationFailureText ? (
        <Alert
          title={
            pending
              ? t("Request outcome is uncertain")
              : t("Request was not created")
          }
          variant="warning"
        >
          {localize(mutationFailureText)}
        </Alert>
      ) : null}
      {pending ? (
        <Surface className="space-y-3 border-warning p-5">
          <h2 className="font-semibold">{t("Reconcile before continuing")}</h2>
          <p className="text-sm leading-6">
            {t(
              "The closure request may have committed. Refresh reads current authority; it never repeats a mutation. An explicit retry re-sends only the original revision with the same idempotency key; a matching replay returns the existing request instead of creating another.",
            )}
          </p>
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={busy || loading}
              onClick={() => void reconcile()}
              variant="secondary"
            >
              {t("Reconcile state")}
            </Button>
            <Button
              disabled={!reconciled || loading || readFailure !== null}
              isLoading={busy}
              loadingLabel={t("Retrying original request")}
              onClick={() => void sendRequest(pending)}
            >
              {t("Retry original request")}
            </Button>
          </div>
        </Surface>
      ) : null}
      {created ? (
        <Surface className="space-y-3 p-5">
          <h2 className="font-heading text-xl font-bold">
            {createdIsNew
              ? t("Closure request created")
              : t("Existing closure request")}
          </h2>
          <p className="text-sm leading-6 text-foreground-secondary">
            {t(
              "A successful request records durable intent and starts recipient notice preparation. It does",
            )}{" "}
            <strong>{t("not")}</strong>{" "}
            {t("close the Publication; track progress on the request page.")}
          </p>
          <dl className="grid gap-3 sm:grid-cols-2">
            <div>
              <dt className="text-xs font-medium text-foreground-muted">
                {t("Closure request ID")}
              </dt>
              <dd className="break-all font-mono text-xs">
                {created.closureRequestId}
              </dd>
            </div>
            <div>
              <dt className="text-xs font-medium text-foreground-muted">
                {t("State")}
              </dt>
              <dd>
                <StateBadge variant={closureStateVariants[created.state]}>
                  {localize(closureStateLabels[created.state])}
                </StateBadge>
              </dd>
            </div>
          </dl>
          <Link
            className={OUTLINE_LINK_CLASSES}
            href={`/app/assessments/${publicationId}/closure/${created.closureRequestId}`}
          >
            {t("Open closure request")}
          </Link>
        </Surface>
      ) : null}
      <Surface className="p-5 sm:p-6">
        <h2 className="mb-4 font-heading text-xl font-bold">
          {t("Authoritative window")}
        </h2>
        <dl className="grid gap-4 sm:grid-cols-2">
          <div>
            <dt className="text-sm font-medium text-foreground-muted">
              {t("Participation end (server authority)")}
            </dt>
            <dd className="mt-1 text-sm">
              {formatUtc(publication.configuration.endAt)}
            </dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-foreground-muted">
              {t("Unlimited attempt duration")}
            </dt>
            <dd className="mt-1 text-sm">
              {publication.configuration.durationMinutes === 0
                ? t("Yes — captured by delayed closure")
                : t("No — bounded attempts finalize by their own deadlines")}
            </dd>
          </div>
        </dl>
        <p className="mt-4 text-sm leading-6 text-foreground-secondary">
          {t(
            "Delayed closure may be requested only after the server's authoritative clock passes the participation end. This screen never decides eligibility from browser time; the server rejects a request that is too early.",
          )}
        </p>
      </Surface>
      {publication.state === "OPEN" ? (
        <Surface className="space-y-4 p-5 sm:p-6">
          <h2 className="font-heading text-xl font-bold">
            {t("Request closure")}
          </h2>
          <p className="text-sm leading-6 text-foreground-secondary">
            {t(
              "A closure request captures every currently ACTIVE unlimited Attempt and queues durable warning preparation for each recipient. The backend publishes each notice asynchronously and confirms it only after proving recipient access; only a confirmed recipient gets a 48-hour cutoff before eligible saved answers may be finalized. An unverifiable recipient may remain unconfirmed indefinitely. Zero captured recipients is valid. While a request is live, schedule changes and new attempts are blocked.",
            )}
          </p>
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={blocked || readFailure !== null}
              onClick={() => setConfirming(true)}
            >
              {t("Review and REQUEST closure")}
            </Button>
            <Link
              className={OUTLINE_LINK_CLASSES}
              href={`/app/assessments/${publicationId}`}
            >
              {t("Use direct CLOSE on Publication details")}
            </Link>
          </div>
        </Surface>
      ) : (
        <Alert
          title={t("Closure requests require an OPEN Publication")}
          variant="info"
        >
          {t(
            "This Publication is {state}. Direct CLOSE and its reconciliation remain on the",
            {
              state: publication.state,
            },
          )}{" "}
          <Link
            className="font-semibold underline"
            href={`/app/assessments/${publicationId}`}
          >
            {t("Publication details")}
          </Link>{" "}
          {t("screen; a delayed-closure workflow applies only while OPEN.")}
        </Alert>
      )}
      <Surface className="p-5 sm:p-6">
        <h2 className="font-heading text-xl font-bold">
          {t("Open a known closure request")}
        </h2>
        <p className="mt-2 text-sm text-foreground-muted">
          {t(
            "There is no closure-request list API. Enter a known request UUID; the server verifies ownership.",
          )}
        </p>
        <form className="mt-4 space-y-4" noValidate onSubmit={openKnownRequest}>
          <TextField
            error={
              knownInvalid
                ? t("Enter a canonical lowercase closure request UUID.")
                : undefined
            }
            label={t("Closure request ID")}
            onChange={(event) => setKnownId(event.target.value)}
            required
            value={knownId}
          />
          <Button type="submit" variant="secondary">
            {t("Open closure request")}
          </Button>
        </form>
      </Surface>
      {confirming ? (
        <TypedConfirmation
          confirmLabel={t("REQUEST delayed closure")}
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
          title={t("Confirm delayed closure request")}
        >
          <p>
            {t(
              "Current authoritative revision: {revision}. The request will be sent with this revision and one durable operation key.",
              { revision: publication.revision },
            )}
          </p>
          <p>
            {t(
              "Requesting closure does not close the Publication. Each recipient first receives a durable notice; the 48-hour period for a recipient starts only when the server confirms their notice is genuinely accessible — never at this button click.",
            )}
          </p>
          <p>
            {t(
              "An ineligible, disabled or unverifiable recipient can block closure indefinitely; that accepted limitation has no force-close or automatic workaround.",
            )}
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
  const { t } = useI18n();
  const closureClient = useClosureClient();
  const scope = `${user?.id}:${getSessionGeneration()}:${publicationId}:${closureRequestId}`;
  return (
    <ApplicationShell currentSection="assessments">
      <div className="mb-6">
        <Link
          className={OUTLINE_LINK_CLASSES}
          href={`/app/assessments/${publicationId}/closure`}
        >
          {t("Back to closure workflow")}
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
          <Button className="mt-5" onClick={() => switchWorkspace("TEACHING")}>
            {t("Switch to Teaching workspace")}
          </Button>
        </Surface>
      ) : closureClient === null ? (
        <Alert title={t("Closure access unavailable")} variant="warning">
          {t(
            "Your authenticated request service is unavailable. Sign in again before continuing.",
          )}
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
  const { t, localize } = useI18n();
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
      <Surface className="p-6">
        <h1 className="font-heading text-3xl font-bold">
          {t("Closure request")}
        </h1>
        {loading ? (
          <div className="mt-5 flex items-center gap-3" role="status">
            <LoadingIndicator label={t("Loading closure request")} />
            {t("Loading authoritative status…")}
          </div>
        ) : (
          <>
            <Alert
              className="mt-5"
              title={t("Closure request could not be loaded")}
              variant="warning"
            >
              {readFailureText
                ? localize(readFailureText)
                : t("Closure request metadata is unavailable.")}
            </Alert>
            <Button
              className="mt-4"
              onClick={() => {
                setLoading(true);
                void refresh();
              }}
              variant="secondary"
            >
              {t("Retry closure request")}
            </Button>
          </>
        )}
      </Surface>
    );

  const blocked = busy || loading || pending !== null;
  const cancellable = canCancelClosure(view) && readFailureText === null;
  const completable = canCompleteClosure(view) && readFailureText === null;
  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="font-heading text-3xl font-bold">
            {t("Closure request")}
          </h1>
          <div className="mt-3">
            <StateBadge variant={closureStateVariants[view.state]}>
              {localize(closureStateLabels[view.state])}
            </StateBadge>
          </div>
          <p className="mt-2 text-sm text-foreground-muted">
            {t("Publication revision after this request: {revision}", {
              revision: view.publicationRevision,
            })}
          </p>
        </div>
        <Button
          disabled={busy}
          isLoading={loading}
          loadingLabel={t("Refreshing authoritative status")}
          onClick={() => void reconcile()}
          variant="secondary"
        >
          {t("Refresh status")}
        </Button>
      </header>
      <p
        aria-live="polite"
        className="text-sm font-medium text-foreground-secondary"
        role="status"
      >
        {localize(status)}
      </p>
      {readFailureText ? (
        <Alert title={t("Status could not be refreshed")} variant="warning">
          {localize(readFailureText)}{" "}
          {t(
            "Previously verified status remains visible; refresh before issuing commands.",
          )}
        </Alert>
      ) : null}
      {mutationFailureText ? (
        <Alert
          title={
            pending
              ? t("Command outcome is uncertain")
              : t("Operation was not confirmed")
          }
          variant="warning"
        >
          {localize(mutationFailureText)}
        </Alert>
      ) : null}
      {pending ? (
        <Surface className="space-y-3 border-warning p-5">
          <h2 className="font-semibold">{t("Reconcile before continuing")}</h2>
          <p className="text-sm leading-6">
            {t(
              "This command may have committed. Refresh reads current authority; it never repeats a mutation. An explicit retry re-sends only the original revision with the same idempotency key, preserving the durable command identity.",
            )}
          </p>
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={busy || loading}
              onClick={() => void reconcile()}
              variant="secondary"
            >
              {t("Reconcile state")}
            </Button>
            <Button
              disabled={!reconciled || loading || readFailureText !== null}
              isLoading={busy}
              loadingLabel={t("Retrying original command")}
              onClick={() => void send(pending)}
            >
              {t("Retry original command")}
            </Button>
          </div>
        </Surface>
      ) : null}
      <Surface className="p-5 sm:p-6">
        <h2 className="mb-5 font-heading text-xl font-bold">
          {t("Durable request record")}
        </h2>
        <dl className="grid gap-4 sm:grid-cols-2">
          <div className="sm:col-span-2">
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Closure request ID (exact)")}
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {view.closureRequestId}
            </dd>
          </div>
          <div className="sm:col-span-2">
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Publication ID")}
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {view.publicationId}
            </dd>
          </div>
          <div className="sm:col-span-2">
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Requesting Teacher identity (as returned by the server)")}
            </dt>
            <dd className="mt-1 break-all font-mono text-xs">
              {view.requestingTeacherId}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Requested at")}
            </dt>
            <dd className="mt-1 text-sm">{formatUtc(view.requestedAt)}</dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Finalization started at")}
            </dt>
            <dd className="mt-1 text-sm">
              {view.finalizationStartedAt === null
                ? t("Not started")
                : formatUtc(view.finalizationStartedAt)}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Resolved at")}
            </dt>
            <dd className="mt-1 text-sm">
              {view.resolvedAt === null
                ? t("Not resolved")
                : formatUtc(view.resolvedAt)}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Authoritative Publication revision")}
            </dt>
            <dd className="mt-1 text-sm">{view.publicationRevision}</dd>
          </div>
        </dl>
        <p className="mt-5 text-sm leading-6 text-foreground-secondary">
          {localize(closureStateDescriptions[view.state])}
        </p>
        <p className="mt-3 text-sm leading-6 text-foreground-muted">
          {t(
            "Recipient counts, per-recipient cutoffs and completion percentages are not disclosed by the server for this workflow; none are shown.",
          )}
        </p>
      </Surface>
      {completion ? (
        <Alert
          title={
            completion.closed
              ? t("Publication CLOSED")
              : t("Bounded progress committed")
          }
          variant={completion.closed ? "success" : "info"}
        >
          {completion.closed
            ? t(
                "The server confirmed the durable CLOSED transition for this Publication.",
              )
            : t(
                "This operation key committed its bounded batch (closed=false). Its acknowledgment is immutable; use the request status and a new deliberate command to continue.",
              )}
        </Alert>
      ) : null}
      {isLiveClosureState(view.state) ? (
        <Surface className="space-y-4 p-5 sm:p-6">
          <h2 className="font-heading text-xl font-bold">
            {t("Manage this request")}
          </h2>
          <p className="text-sm leading-6 text-foreground-secondary">
            {t(
              "COMPLETE reconciles overdue bounded work and processes due confirmed recipients in bounded batches; only the owning Teacher's command can commit the final CLOSED transition. CANCEL is possible only before the first closure-driven finalization.",
            )}
          </p>
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={blocked || !completable}
              onClick={() => setConfirming("complete")}
            >
              {t("Review and COMPLETE closure")}
            </Button>
            <Button
              disabled={blocked || !cancellable}
              onClick={() => setConfirming("cancel")}
              variant="danger"
            >
              {t("Review and CANCEL request")}
            </Button>
          </div>
          {!cancellable && view.state === "PENDING" ? (
            <p className="text-sm text-foreground-muted">
              {t(
                "Cancellation is unavailable because finalization has started.",
              )}
            </p>
          ) : null}
          {view.state === "FINALIZING" ? (
            <p className="text-sm text-foreground-muted">
              {t(
                "The request is FINALIZING; cancellation can no longer win. Use COMPLETE to continue bounded batches.",
              )}
            </p>
          ) : null}
        </Surface>
      ) : (
        <Alert title={t("This request is resolved")} variant="info">
          {t(
            "Resolved workflow states never return to live states. A replacement, if still needed, is a new request with fresh notices and fresh 48-hour periods.",
          )}
        </Alert>
      )}
      {confirming !== null ? (
        <TypedConfirmation
          confirmLabel={
            confirming === "cancel"
              ? t("CANCEL closure request")
              : t("COMPLETE closure")
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
              ? t("Confirm cancellation")
              : t("Confirm completion command")
          }
        >
          <p>
            {t(
              "Command revision: {revision}. The command is sent with this authoritative revision and one durable operation key.",
              { revision: view.publicationRevision },
            )}
          </p>
          {confirming === "cancel" ? (
            <p>
              {t(
                "Cancellation resolves this request while preserving published notices to recipients as non-actionable history. A later replacement is a new request with new notices and fresh 48-hour periods.",
              )}
            </p>
          ) : (
            <p>
              {t(
                "Completion processes eligible due work in bounded batches. If confirmed notices or cutoffs are still outstanding, the server answers CLOSURE_NOT_READY without undoing committed progress. The Publication closes only when every Attempt is terminal and every captured member is resolved.",
              )}
            </p>
          )}
        </TypedConfirmation>
      ) : null}
    </div>
  );
}
