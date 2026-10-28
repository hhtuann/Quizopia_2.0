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
import { formatInstant } from "../../../lib/i18n/core";
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
            "You need the Teacher role and ownership of this assessment. Switching workspace does not grant a role.",
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
            {t("Closure management lives in the Teaching workspace.")}
          </p>
          <Button className="mt-5" onClick={() => switchWorkspace("TEACHING")}>
            {t("Switch to Teaching workspace")}
          </Button>
        </Surface>
      ) : publicationClient === null || closureClient === null ? (
        <Alert title={t("Closure access unavailable")} variant="warning">
          {t("Your session has ended. Please sign in again.")}
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
  const { t, localize, locale } = useI18n();
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
    setStatus("Sending your request…");
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
          ? "Request created. The assessment is not closed yet; notices are being prepared."
          : "This request already existed; the existing one is shown.",
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
          ? "Too many requests. Checking the current state; retry repeats your original request."
          : uncertain
            ? "Outcome unclear. Checking the current state before another action."
            : conflict
              ? "Something changed. Checking the latest details before another action."
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
                ? localize(readFailure)
                : t("Publication details are unavailable.")}{" "}
              {t("The closure workflow requires an assessment you own.")}
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
          loadingLabel={t("Refreshing details")}
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
          {localize(readFailure)} {t("The details below may be outdated.")}
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
              "This request may have gone through. Refresh shows the current state without repeating it. Retry repeats only your original request — if it already succeeded, the existing request is returned.",
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
              "A successful request starts preparing student notices. It does",
            )}{" "}
            <strong>{t("not")}</strong>{" "}
            {t("close the assessment; track progress on the request page.")}
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
          {t("Participation window")}
        </h2>
        <dl className="grid gap-4 sm:grid-cols-2">
          <div>
            <dt className="text-sm font-medium text-foreground-muted">
              {t("Participation end")}
            </dt>
            <dd className="mt-1 text-sm">
              {formatInstant(locale, publication.configuration.endAt, {
                withTimeZone: true,
              })}
            </dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-foreground-muted">
              {t("Unlimited attempt duration")}
            </dt>
            <dd className="mt-1 text-sm">
              {publication.configuration.durationMinutes === 0
                ? t("Yes — handled by delayed closure")
                : t("No — bounded attempts end at their own deadlines")}
            </dd>
          </div>
        </dl>
        <p className="mt-4 text-sm leading-6 text-foreground-secondary">
          {t(
            "Delayed closure can be requested only after the participation end has passed. Quizopia uses its own clock, not your device's; a too-early request is rejected.",
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
              "A closure request covers every active unlimited attempt. Each student first gets a notice; their 48-hour period starts only after the notice is confirmed accessible. A student who cannot be verified can delay closure indefinitely — there is no force-close. While a request is live, schedule changes and new attempts are blocked.",
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
          {t("This assessment is {state}. Direct CLOSE stays on the", {
            state: publication.state,
          })}{" "}
          <Link
            className="font-semibold underline"
            href={`/app/assessments/${publicationId}`}
          >
            {t("Publication details")}
          </Link>{" "}
          {t("page; delayed closure applies only while OPEN.")}
        </Alert>
      )}
      <Surface className="p-5 sm:p-6">
        <h2 className="font-heading text-xl font-bold">
          {t("Open a known closure request")}
        </h2>
        <p className="mt-2 text-sm text-foreground-muted">
          {t(
            "Requests are opened by ID. Paste a closure request ID you saved; ownership is verified.",
          )}
        </p>
        <form className="mt-4 space-y-4" noValidate onSubmit={openKnownRequest}>
          <TextField
            error={
              knownInvalid
                ? t("Enter the closure request ID exactly as saved.")
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
              "This request uses revision {revision}. Retries repeat exactly this request.",
              { revision: publication.revision },
            )}
          </p>
          <p>
            {t(
              "Requesting closure does not close the assessment yet. Each student first receives a notice; their 48-hour period starts only when that notice is confirmed accessible — not at this click.",
            )}
          </p>
          <p>
            {t(
              "A recipient who cannot be verified can block closure indefinitely; there is no force-close.",
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
          {t("You need the Teacher role and ownership of this assessment.")}{" "}
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
          {t("Your session has ended. Please sign in again.")}
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
  const { t, localize, locale } = useI18n();
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
    setStatus("Sending your command…");
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
        setStatus("The request was cancelled.");
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
            ? "The assessment is now CLOSED."
            : "Progress was saved, but the assessment is not CLOSED yet. Check the status and run COMPLETE again to continue.",
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
        ? "Too many requests. Checking the current state; retry repeats your original request."
        : uncertain
          ? "Outcome unclear. Checking the current state before another action."
          : conflict
            ? "Something changed. Checking the latest status before another action."
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
          "The latest status confirms the command already took effect; nothing was repeated.",
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
        "The latest status confirms the command already took effect; nothing was repeated.",
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
            {t("Loading status…")}
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
                : t("Closure request details are unavailable.")}
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
          loadingLabel={t("Refreshing status")}
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
          {localize(readFailureText)} {t("The status above may be outdated.")}
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
              "This command may have gone through. Refresh shows the current state without repeating it. Retry repeats only your original command — if it already succeeded, nothing changes.",
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
          {t("Request record")}
        </h2>
        <dl className="grid gap-4 sm:grid-cols-2">
          <div className="sm:col-span-2">
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Closure request ID")}
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
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Requested at")}
            </dt>
            <dd className="mt-1 text-sm">
              {formatInstant(locale, view.requestedAt)}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Finalization started at")}
            </dt>
            <dd className="mt-1 text-sm">
              {view.finalizationStartedAt === null
                ? t("Not started")
                : formatInstant(locale, view.finalizationStartedAt)}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Resolved at")}
            </dt>
            <dd className="mt-1 text-sm">
              {view.resolvedAt === null
                ? t("Not resolved")
                : formatInstant(locale, view.resolvedAt)}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-foreground-muted">
              {t("Publication revision")}
            </dt>
            <dd className="mt-1 text-sm">{view.publicationRevision}</dd>
          </div>
        </dl>
        <p className="mt-5 text-sm leading-6 text-foreground-secondary">
          {localize(closureStateDescriptions[view.state])}
        </p>
        <p className="mt-3 text-sm leading-6 text-foreground-muted">
          {t(
            "Per-student cutoffs and completion percentages are not shown in this workflow.",
          )}
        </p>
      </Surface>
      {completion ? (
        <Alert
          title={
            completion.closed ? t("Publication CLOSED") : t("Progress saved")
          }
          variant={completion.closed ? "success" : "info"}
        >
          {completion.closed
            ? t("The assessment is now CLOSED.")
            : t(
                "Progress was saved, but the assessment is not CLOSED yet. Check the status and run COMPLETE again to continue.",
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
              "COMPLETE processes the next batch of due work; only your command can make the final CLOSED transition. CANCEL works only before finalization starts.",
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
                "The request is FINALIZING and can no longer be cancelled. Use COMPLETE to continue.",
              )}
            </p>
          ) : null}
        </Surface>
      ) : (
        <Alert title={t("This request is resolved")} variant="info">
          {t(
            "Resolved requests stay resolved. If needed, start a new request with fresh notices and 48-hour periods.",
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
              "This command uses revision {revision}. Retries repeat exactly this command.",
              { revision: view.publicationRevision },
            )}
          </p>
          {confirming === "cancel" ? (
            <p>
              {t(
                "Cancelling resolves this request. Students keep their notices as history only. A later replacement is a new request with new notices and 48-hour periods.",
              )}
            </p>
          ) : (
            <p>
              {t(
                "COMPLETE processes due work in batches. If notices or cutoffs are still outstanding, it returns CLOSURE_NOT_READY and keeps progress. The assessment closes only when every attempt is finished and every included student is resolved.",
              )}
            </p>
          )}
        </TypedConfirmation>
      ) : null}
    </div>
  );
}
