"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import {
  Button,
  OUTLINE_LINK_CLASSES,
  PRIMARY_LINK_CLASSES,
} from "../../../components/ui/button";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
import {
  formatDecimal,
  formatInstant,
  formatNumber,
} from "../../../lib/i18n/core";
import { useI18n } from "../../../lib/i18n/provider";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import { PublicationConfirmation } from "../../assessments/components/publication-confirmation";
import {
  StudentCamera,
  useStudentMonitoring,
  type MonitoringEligibility,
} from "../../proctoring";
import type {
  Answer,
  AttemptView,
  Result,
  Review,
  StudentView,
  SubmitRequest,
} from "../api/attempt-contract.generated";
import {
  attemptFailureMessage,
  isUncertainAttemptMutation,
  type AttemptApiClient,
  type AttemptFailure,
} from "../api/attempt-api-client";
import { useAttemptClient } from "../hooks/use-attempt-client";
import { useAttemptCoordinator } from "../hooks/use-attempt-coordinator";
import { useAttemptEphemeralContext } from "../hooks/attempt-ephemeral-context";
import { useUnsavedNavigationGuard } from "../hooks/use-unsaved-navigation-guard";
import {
  useRealtimeResync,
  useRealtimeSubscriptions,
} from "../../realtime/hooks/use-realtime-subscriptions";
import { RealtimeStatusInline } from "../../realtime/components/realtime-status";
import type { RealtimeResource } from "../../realtime/model/realtime-frame";
import {
  effectiveAttemptDeadline,
  canonicalUuid,
  isUnanswered,
  isValidNumericToken,
  newIdempotencyKey,
  normalizeNumericInput,
  privateLocatorSchema,
} from "../model/attempt";
import {
  NO_ATTEMPT_MONITORING,
  type AttemptMonitoringObserver,
} from "../model/monitoring-boundary";
import { AnswerSummary } from "./answer-fields";
import {
  aggregateSavePhase,
  AttemptWorkspaceHeader,
} from "./attempt-workspace-header";
import {
  AttemptQuestionNavigator,
  scrollQuestionIntoView,
} from "./attempt-question-navigator";
import { AttemptQuestionList } from "./attempt-question-list";
import { useOptionalObservations } from "../audit/use-optional-observations";

type EntryMode = "PUBLIC" | "CLASS";

// Load effects must not refetch when only the locale changes (that would
// discard answers or in-flight attempt state), so they format failure copy
// through the latest localizer via a stable ref instead of a dependency.
function useLocalizeRef() {
  const { localize } = useI18n();
  const ref = useRef(localize);
  useLayoutEffect(() => {
    ref.current = localize;
  }, [localize]);
  return ref;
}

/** Blocking UI for students lacking STUDENT authority or the LEARNING
 * workspace, shared by the shell pages and the focused attempt workspace. */
function useStudentAccessBlock() {
  const { activeWorkspace, switchWorkspace, user } = useAuth();
  const { t } = useI18n();
  if (!user?.roles.includes("STUDENT"))
    return (
      <Alert title={t("Student access required")} variant="warning">
        {t("Please sign in with a Student account to continue.")}
      </Alert>
    );
  if (activeWorkspace !== "LEARNING")
    return (
      <Surface className="mx-auto max-w-2xl p-6 sm:p-8">
        <h1 className="font-heading text-2xl font-bold">
          {t("Open the Learning workspace")}
        </h1>
        <p className="mt-3 leading-7 text-foreground-secondary">
          {t("Switch workspace to organize your learning.")}
        </p>
        <Button className="mt-5" onClick={() => switchWorkspace("LEARNING")}>
          {t("Switch to Learning")}
        </Button>
      </Surface>
    );
  return null;
}

function StudentPageBoundary({
  children,
}: {
  readonly children: React.ReactNode;
}) {
  return (
    <AuthenticatedBoundary>
      <ApplicationShell currentSection="assessments">
        <StudentWorkspace>{children}</StudentWorkspace>
      </ApplicationShell>
    </AuthenticatedBoundary>
  );
}

function StudentWorkspace({
  children,
}: {
  readonly children: React.ReactNode;
}) {
  const blocked = useStudentAccessBlock();
  return blocked ?? children;
}

/**
 * Focused workspace for the ACTIVE attempt route: no standard application
 * navbar. Authenticated/session gates still apply; the workspace header
 * supplies the only chrome (brand, back-to-app, language, attempt status).
 */
function StudentFocusedWorkspace({
  children,
}: {
  readonly children: React.ReactNode;
}) {
  const router = useRouter();
  const { t } = useI18n();
  const blocked = useStudentAccessBlock();
  return (
    <AuthenticatedBoundary>
      <main
        className="flex min-h-dvh min-w-0 flex-col bg-background"
        id="main-content"
        tabIndex={-1}
      >
        {blocked === null ? (
          children
        ) : (
          <>
            <AttemptWorkspaceHeader
              actionsDisabled
              answeredCount={null}
              onAbandon={undefined}
              onBack={() => router.push("/app")}
              onSubmit={undefined}
              realtime={null}
              remainingLabel={null}
              savePhase={null}
              showActions={false}
              studentName={null}
              title={t("Assessment")}
              totalCount={null}
            />
            <div className="w-full px-4 py-10 sm:px-6 lg:px-8">{blocked}</div>
          </>
        )}
      </main>
    </AuthenticatedBoundary>
  );
}

function ClientUnavailable() {
  const { t } = useI18n();
  return (
    <Alert title={t("Assessment access unavailable")} variant="warning">
      {t("Your session has ended. Please sign in again.")}
    </Alert>
  );
}

function PublicationPreview({
  publication,
}: {
  readonly publication: StudentView;
}) {
  const { t, locale } = useI18n();
  const entries = [
    [t("Audience"), publication.audience],
    [t("Availability"), publication.windowStatus.replaceAll("_", " ")],
    [
      t("Attempt duration"),
      publication.durationMinutes === 0
        ? t("Unlimited; closing rules still apply")
        : t("{minutes} minutes", {
            minutes: formatNumber(locale, publication.durationMinutes),
          }),
    ],
    [
      t("Monitoring"),
      publication.monitoringRequired ? t("Required") : t("Not required"),
    ],
  ];
  return (
    <Surface
      className="p-6 sm:p-8"
      role="region"
      aria-labelledby="entry-preview"
    >
      <p className="text-xs font-bold uppercase tracking-[0.14em] text-primary">
        {t("Authorized preview")}
      </p>
      <h2 className="mt-2 font-heading text-2xl font-bold" id="entry-preview">
        {publication.title}
      </h2>
      {publication.description ? (
        <p className="mt-3 whitespace-pre-wrap leading-7 text-foreground-secondary">
          {publication.description}
        </p>
      ) : null}
      <dl className="mt-6 grid gap-4 sm:grid-cols-2">
        {entries.map(([label, value]) => (
          <div
            className="rounded-xl border border-border bg-background p-4"
            key={label}
          >
            <dt className="text-xs font-semibold uppercase tracking-wide text-foreground-muted">
              {label}
            </dt>
            <dd className="mt-1 text-sm font-semibold text-foreground">
              {value}
            </dd>
          </div>
        ))}
        <div className="rounded-xl border border-border bg-background p-4">
          <dt className="text-xs font-semibold uppercase tracking-wide text-foreground-muted">
            {t("Starts")}
          </dt>
          <dd className="mt-1 text-sm text-foreground">
            <time dateTime={publication.startAt}>
              {formatInstant(locale, publication.startAt, {
                withTimeZone: true,
              })}
            </time>
          </dd>
        </div>
        <div className="rounded-xl border border-border bg-background p-4">
          <dt className="text-xs font-semibold uppercase tracking-wide text-foreground-muted">
            {t("Entry window ends")}
          </dt>
          <dd className="mt-1 text-sm text-foreground">
            <time dateTime={publication.endAt}>
              {formatInstant(locale, publication.endAt, {
                withTimeZone: true,
              })}
            </time>
          </dd>
        </div>
      </dl>
    </Surface>
  );
}

interface CreationIntent {
  readonly input: { readonly locator?: string };
  readonly key: string;
  readonly publication: StudentView;
}

export function StudentAssessmentJoinScreen({
  client,
}: {
  readonly client: AttemptApiClient;
}) {
  const router = useRouter();
  const { t } = useI18n();
  const localizeRef = useLocalizeRef();
  const { rememberPublicLocator, rememberPublicationTitle } =
    useAttemptEphemeralContext();
  const [mode, setMode] = useState<EntryMode>("PUBLIC");
  const [locator, setLocator] = useState("");
  const [publicationId, setPublicationId] = useState("");
  const [preview, setPreview] = useState<StudentView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"entry" | "start" | null>(null);
  const [uncertain, setUncertain] = useState<CreationIntent | null>(null);
  useUnsavedNavigationGuard(
    uncertain !== null || busy === "start",
    t("This start action may not have finished. Leave anyway?"),
  );

  const enter = async (event: FormEvent) => {
    event.preventDefault();
    if (uncertain !== null) {
      setError(
        localizeRef.current(
          "Retry the unfinished start before checking another assessment.",
        ),
      );
      return;
    }
    setError(null);
    const valid =
      mode === "PUBLIC"
        ? privateLocatorSchema.safeParse(locator).success
        : canonicalUuid.safeParse(publicationId).success;
    if (!valid) {
      setError(
        localizeRef.current(
          mode === "PUBLIC"
            ? "Enter the exact 43-character private locator."
            : "Enter the Publication ID exactly as given (lowercase).",
        ),
      );
      return;
    }
    setBusy("entry");
    const result =
      mode === "PUBLIC"
        ? await client.enterPublic({ locator })
        : await client.enterClass(publicationId);
    setBusy(null);
    if (!result.ok) {
      setPreview(null);
      setError(attemptFailureMessage(result.error, localizeRef.current));
      return;
    }
    setPreview(result.value);
    // Thread the authorized preview title into session memory so the focused
    // attempt workspace can show the real exam title (AttemptView has none).
    rememberPublicationTitle(result.value.publicationId, result.value.title);
    setUncertain(null);
  };

  const runStart = async (intent: CreationIntent) => {
    setBusy("start");
    setError(null);
    const result = await client.start(
      intent.publication.publicationId,
      intent.input,
      intent.key,
    );
    setBusy(null);
    if (!result.ok) {
      if (isUncertainAttemptMutation(result.error)) setUncertain(intent);
      else setUncertain(null);
      setError(attemptFailureMessage(result.error, localizeRef.current));
      return;
    }
    if (intent.input.locator)
      rememberPublicLocator(
        intent.publication.publicationId,
        intent.input.locator,
      );
    setUncertain(null);
    router.push(`/app/attempts/${result.value.attemptId}`);
  };

  const start = () => {
    if (preview === null) return;
    const input = preview.audience === "PUBLIC" ? { locator } : {};
    void runStart({ input, key: newIdempotencyKey(), publication: preview });
  };

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div>
        <h1 className="font-heading text-3xl font-extrabold tracking-[-0.03em] sm:text-4xl">
          {t("Enter an assessment")}
        </h1>
        <p className="mt-3 max-w-2xl leading-7 text-foreground-secondary">
          {t("Public assessments still require a signed-in Student.")}
        </p>
      </div>

      <Surface className="p-6 sm:p-8">
        <div
          className="flex flex-wrap gap-2"
          role="tablist"
          aria-label={t("Entry method")}
        >
          {(["PUBLIC", "CLASS"] as const).map((value) => (
            <button
              aria-selected={mode === value}
              className={`min-h-11 rounded-full border px-4 text-sm font-semibold focus-visible:ring-2 focus-visible:ring-focus ${
                mode === value
                  ? "border-primary bg-primary/10 text-primary"
                  : "border-border bg-surface text-foreground-secondary"
              }`}
              key={value}
              disabled={uncertain !== null}
              onClick={() => {
                setMode(value);
                setPreview(null);
                setError(null);
                setUncertain(null);
              }}
              role="tab"
              type="button"
            >
              {value === "PUBLIC"
                ? t("Private locator")
                : t("Known class Publication")}
            </button>
          ))}
        </div>
        <form className="mt-6 space-y-5" noValidate onSubmit={enter}>
          {mode === "PUBLIC" ? (
            <TextField
              autoComplete="off"
              error={error ?? undefined}
              helperText={t(
                "The locator is kept in memory only and is never saved.",
              )}
              label={t("43-character private locator")}
              maxLength={43}
              minLength={43}
              onChange={(event) => setLocator(event.target.value)}
              required
              spellCheck={false}
              type="password"
              value={locator}
            />
          ) : (
            <TextField
              autoComplete="off"
              error={error ?? undefined}
              helperText={t("Ask your instructor for the Publication ID.")}
              label={t("Publication ID")}
              onChange={(event) => setPublicationId(event.target.value)}
              required
              spellCheck={false}
              value={publicationId}
            />
          )}
          <Button
            isLoading={busy === "entry"}
            loadingLabel={t("Checking assessment")}
            type="submit"
          >
            {t("Check access")}
          </Button>
        </form>
      </Surface>

      {preview ? (
        <>
          <PublicationPreview publication={preview} />
          {preview.windowStatus !== "AVAILABLE" ? (
            <Alert
              title={t("Starting is not currently available")}
              variant="warning"
            >
              {t(
                "Availability is {status}, checked by Quizopia — not your device clock.",
                { status: preview.windowStatus.replaceAll("_", " ") },
              )}
            </Alert>
          ) : null}
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={
                preview.windowStatus !== "AVAILABLE" || uncertain !== null
              }
              isLoading={busy === "start"}
              loadingLabel={t("Starting attempt")}
              onClick={start}
            >
              {t("Start or resume")}
            </Button>
            {uncertain ? (
              <Button
                isLoading={busy === "start"}
                onClick={() => void runStart(uncertain)}
                variant="secondary"
              >
                {t("Retry same start request")}
              </Button>
            ) : null}
          </div>
        </>
      ) : null}
      {uncertain ? (
        <Alert title={t("Start outcome is uncertain")} variant="warning">
          {t(
            "Your original request is kept in memory. Retry the same request; do not start a new one.",
          )}
        </Alert>
      ) : null}
    </div>
  );
}

export function StudentAssessmentJoinPage() {
  const client = useAttemptClient();
  const { getSessionGeneration, user } = useAuth();
  const scope = `${user?.id ?? "none"}:${getSessionGeneration() ?? "none"}`;
  return (
    <StudentPageBoundary>
      {client === null ? (
        <ClientUnavailable />
      ) : (
        <StudentAssessmentJoinScreen client={client} key={scope} />
      )}
    </StudentPageBoundary>
  );
}

/** Minimal workspace chrome for load/error states before the attempt loads. */
function FocusedWorkspaceFrame({
  children,
}: {
  readonly children: React.ReactNode;
}) {
  const router = useRouter();
  const { t } = useI18n();
  return (
    <div className="flex min-h-dvh flex-col">
      <AttemptWorkspaceHeader
        actionsDisabled
        answeredCount={null}
        onAbandon={undefined}
        onBack={() => router.push("/app")}
        onSubmit={undefined}
        realtime={null}
        remainingLabel={null}
        savePhase={null}
        showActions={false}
        studentName={null}
        title={t("Assessment")}
        totalCount={null}
      />
      <div className="w-full flex-1 px-4 py-10 sm:px-6 lg:px-8">{children}</div>
    </div>
  );
}

function AttemptLoading() {
  const { t } = useI18n();
  return (
    <Surface className="p-8">
      <LoadingIndicator label={t("Loading your attempt")} />
    </Surface>
  );
}

export function StudentAttemptPage({
  attemptId,
  monitoring = NO_ATTEMPT_MONITORING,
}: {
  readonly attemptId: string;
  readonly monitoring?: AttemptMonitoringObserver;
}) {
  const client = useAttemptClient();
  const { getSessionGeneration, user } = useAuth();
  const scope = `${user?.id ?? "none"}:${getSessionGeneration() ?? "none"}:${attemptId}`;
  return (
    <StudentFocusedWorkspace>
      {client === null ? (
        <ClientUnavailableFocused />
      ) : (
        <AttemptLoader
          attemptId={attemptId}
          client={client}
          key={scope}
          monitoring={monitoring}
        />
      )}
    </StudentFocusedWorkspace>
  );
}

/** ClientUnavailable padded for the focused workspace (no shell container). */
function ClientUnavailableFocused() {
  const { t } = useI18n();
  return (
    <div className="mx-auto w-full max-w-2xl px-4 py-10 sm:px-6">
      <Alert title={t("Assessment access unavailable")} variant="warning">
        {t(
          "The authenticated request service is unavailable. Sign in again before continuing.",
        )}
      </Alert>
    </div>
  );
}

function AttemptLoader({
  attemptId,
  client,
  monitoring,
}: {
  readonly attemptId: string;
  readonly client: AttemptApiClient;
  readonly monitoring: AttemptMonitoringObserver;
}) {
  const { t } = useI18n();
  const localizeRef = useLocalizeRef();
  const invalidAttemptId = !canonicalUuid.safeParse(attemptId).success;
  const { getPublicLocator } = useAttemptEphemeralContext();
  const [attempt, setAttempt] = useState<AttemptView | null>(null);
  const [monitoringProjection, setMonitoringProjection] =
    useState<AttemptMonitoringProjection>({ status: "checking" });
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    if (invalidAttemptId) return () => controller.abort();
    void client
      .getAttempt(attemptId, controller.signal)
      .then(async (result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) {
          setError(attemptFailureMessage(result.error, localizeRef.current));
          return;
        }
        setAttempt(result.value);
        if (
          !isPotentiallyMonitoredAttempt(result.value) ||
          getPublicLocator(result.value.publicationId) !== null
        ) {
          setMonitoringProjection({ status: "not-required" });
          return;
        }
        const publication = await client.enterClass(
          result.value.publicationId,
          controller.signal,
        );
        if (controller.signal.aborted) return;
        if (!publication.ok) {
          setMonitoringProjection(
            publication.error.kind === "api" && publication.error.status === 404
              ? { status: "not-required" }
              : { status: "unavailable" },
          );
          return;
        }
        setMonitoringProjection(
          publication.value.audience === "CLASS" &&
            publication.value.monitoringRequired
            ? {
                status: "eligible",
                eligibility: {
                  attemptId: result.value.attemptId,
                  audience: publication.value.audience,
                  monitoringRequired: publication.value.monitoringRequired,
                  attemptStatus: result.value.state,
                  deadlineAt: result.value.deadlineAt,
                },
              }
            : { status: "not-required" },
        );
      });
    return () => controller.abort();
  }, [attemptId, client, getPublicLocator, invalidAttemptId, localizeRef]);
  if (invalidAttemptId || error)
    return (
      <FocusedWorkspaceFrame>
        <Alert title={t("Attempt unavailable")} variant="danger">
          {invalidAttemptId
            ? t("The Attempt ID is invalid.")
            : (error ?? undefined)}
        </Alert>
      </FocusedWorkspaceFrame>
    );
  if (attempt === null)
    return (
      <FocusedWorkspaceFrame>
        <AttemptLoading />
      </FocusedWorkspaceFrame>
    );
  return monitoring === NO_ATTEMPT_MONITORING ? (
    <IntegratedAttemptScreen
      attempt={attempt}
      client={client}
      projection={monitoringProjection}
    />
  ) : (
    <AttemptScreen attempt={attempt} client={client} monitoring={monitoring} />
  );
}

type AttemptMonitoringProjection =
  | { readonly status: "checking" | "not-required" | "unavailable" }
  | {
      readonly status: "eligible";
      readonly eligibility: MonitoringEligibility;
    };

function isPotentiallyMonitoredAttempt(attempt: AttemptView): boolean {
  if (attempt.state !== "ACTIVE" || attempt.deadlineAt === null) return false;
  const effectiveDeadline = effectiveAttemptDeadline(attempt);
  const deadline =
    effectiveDeadline === null ? null : Date.parse(effectiveDeadline);
  const serverNow = Date.parse(attempt.serverNow);
  return (
    deadline !== null &&
    Number.isFinite(deadline) &&
    Number.isFinite(serverNow) &&
    serverNow < deadline
  );
}

export function IntegratedAttemptScreen({
  attempt,
  client,
  projection,
}: {
  readonly attempt: AttemptView;
  readonly client: AttemptApiClient;
  readonly projection: AttemptMonitoringProjection;
}) {
  const monitoring = useStudentMonitoring();
  const monitoringRef = useRef(monitoring);
  const [collectionAllowed, setCollectionAllowed] = useState(() =>
    isPotentiallyMonitoredAttempt(attempt),
  );
  const lifecycle = useRef({
    collectionAllowed: isPotentiallyMonitoredAttempt(attempt),
    selectedQuestionNumber: attempt.questions[0]?.number ?? 1,
  });

  const eligibility =
    projection.status === "eligible" ? projection.eligibility : null;
  const eligibilityRef = useRef(eligibility);

  useEffect(() => {
    monitoringRef.current = monitoring;
  }, [monitoring]);

  useEffect(() => {
    eligibilityRef.current = eligibility;
  }, [eligibility]);

  const ensureStarted = useCallback(() => {
    const currentEligibility = eligibilityRef.current;
    const currentMonitoring = monitoringRef.current;
    if (
      currentEligibility === null ||
      attempt.state !== "ACTIVE" ||
      !lifecycle.current.collectionAllowed ||
      currentMonitoring.state.status === "starting" ||
      currentMonitoring.state.status === "active" ||
      currentMonitoring.state.status === "ended" ||
      currentMonitoring.state.status === "ineligible"
    )
      return;
    if (
      currentMonitoring.state.status === "unavailable" &&
      currentMonitoring.state.error.kind === "api" &&
      (currentMonitoring.state.error.status === 403 ||
        currentMonitoring.state.error.code === "AUTHORITY_LOST")
    )
      return;
    void currentMonitoring.start(currentEligibility);
  }, [attempt.state]);

  const ensureStopped = useCallback(() => {
    const current = monitoringRef.current;
    if (
      current.state.status === "active" ||
      current.state.status === "starting"
    )
      void current.stop();
  }, []);

  useEffect(() => {
    if (eligibility === null) ensureStopped();
    else ensureStarted();
  }, [eligibility, ensureStarted, ensureStopped]);

  useEffect(() => {
    if (monitoring.state.status === "active")
      monitoringRef.current.questionViewed(
        lifecycle.current.selectedQuestionNumber,
      );
  }, [monitoring.state.status]);

  const observer = useMemo<AttemptMonitoringObserver>(
    () => ({
      enabled: eligibility !== null,
      onAttemptLifecycle(event) {
        lifecycle.current = {
          collectionAllowed: event.collectionAllowed,
          selectedQuestionNumber: event.selectedQuestionNumber,
        };
        setCollectionAllowed(
          event.collectionAllowed && event.state === "ACTIVE",
        );
        if (event.collectionAllowed && event.state === "ACTIVE")
          ensureStarted();
        else ensureStopped();
      },
      onQuestionSelection(event) {
        const previous = lifecycle.current.selectedQuestionNumber;
        lifecycle.current = {
          ...lifecycle.current,
          selectedQuestionNumber: event.questionNumber,
        };
        const current = monitoringRef.current;
        if (current.state.status !== "active") return;
        if (previous !== event.questionNumber) current.questionLeft(previous);
        current.questionViewed(event.questionNumber);
      },
      onAnswerInteraction(event) {
        const current = monitoringRef.current;
        if (current.state.status !== "active") return;
        if (event.type === "selected")
          current.answerSelected(event.questionNumber);
        else if (event.type === "cleared")
          current.answerCleared(event.questionNumber);
        else current.answerChanged(event.questionNumber);
      },
    }),
    [eligibility, ensureStarted, ensureStopped],
  );

  const isEligibleLifecycle =
    eligibility !== null &&
    collectionAllowed &&
    attempt.state === "ACTIVE" &&
    isPotentiallyMonitoredAttempt(attempt);

  const isAuthorityLost =
    monitoring.state.status === "unavailable" &&
    monitoring.state.error.kind === "api" &&
    (monitoring.state.error.status === 403 ||
      monitoring.state.error.code === "AUTHORITY_LOST");

  const canRetry =
    isEligibleLifecycle &&
    monitoring.state.status === "unavailable" &&
    !isAuthorityLost;

  return (
    <AttemptScreen
      attempt={attempt}
      client={client}
      monitoring={observer}
      monitoringContent={
        <AttemptMonitoringStatus
          canRetry={canRetry}
          onRetry={ensureStarted}
          projection={projection}
          state={monitoring.state}
        />
      }
    />
  );
}

function AttemptMonitoringStatus({
  canRetry = false,
  onRetry,
  projection,
  state,
}: {
  readonly canRetry?: boolean;
  readonly onRetry: () => void;
  readonly projection: AttemptMonitoringProjection;
  readonly state: ReturnType<typeof useStudentMonitoring>["state"];
}) {
  const { t } = useI18n();
  if (projection.status === "not-required") return null;
  if (projection.status === "checking")
    return (
      <Alert title={t("Checking monitoring requirement")} variant="info">
        {t(
          "You can start working while monitoring is being checked. Nothing is being recorded yet.",
        )}
      </Alert>
    );
  if (projection.status === "unavailable")
    return (
      <Alert title={t("Monitoring requirement unavailable")} variant="warning">
        {t(
          "Monitoring could not be verified, so recording stays off. Saving and submitting are not affected.",
        )}
      </Alert>
    );
  if (state.status === "active")
    return (
      <div className="space-y-5">
        <Alert title={t("Activity monitoring active")} variant="info">
          {t(
            "Monitoring is on for this class attempt. Browser signals help with review and are never proof of cheating. Saving and submitting never wait for monitoring.",
          )}
        </Alert>
        <StudentCamera activity={state.activity} session={state.session} />
      </div>
    );
  if (
    state.status === "unavailable" ||
    state.status === "ended" ||
    state.status === "ineligible"
  )
    return (
      <Alert title={t("Monitoring unavailable")} variant="warning">
        {t(
          "No activity observer or camera is active. This does not change answers, grading, or deadlines.",
        )}
        {canRetry ? (
          <div className="mt-3">
            <Button onClick={onRetry} size="compact" variant="secondary">
              {t("Retry monitoring verification")}
            </Button>
          </div>
        ) : null}
      </Alert>
    );
  return (
    <Alert title={t("Starting activity monitoring")} variant="info">
      {t("Checking permission before any monitoring starts.")}
    </Alert>
  );
}

function useInformativeCountdown(
  attempt: AttemptView,
  refresh: () => Promise<unknown>,
) {
  const target = effectiveAttemptDeadline(attempt);
  const activeTarget = attempt.state === "ACTIVE" ? target : null;
  const [remaining, setRemaining] = useState<number | null>(() =>
    activeTarget === null
      ? null
      : Math.max(0, Date.parse(activeTarget) - Date.parse(attempt.serverNow)),
  );
  useEffect(() => {
    if (activeTarget === null) return;
    const serverAtLoad = Date.parse(attempt.serverNow);
    const targetTime = Date.parse(activeTarget);
    const localAtLoad = Date.now();
    let reconciled = false;
    const tick = () => {
      const estimate = targetTime - (serverAtLoad + (Date.now() - localAtLoad));
      setRemaining(Math.max(0, estimate));
      if (estimate <= 0) {
        if (!reconciled) {
          reconciled = true;
          void refresh();
        }
      }
    };
    const initial = setTimeout(tick, 0);
    const timer = setInterval(tick, 1_000);
    return () => {
      clearTimeout(initial);
      clearInterval(timer);
    };
  }, [activeTarget, attempt.serverNow, refresh]);
  const presentedRemaining = activeTarget === null ? null : remaining;
  return {
    remaining: presentedRemaining,
    expired: activeTarget !== null && presentedRemaining === 0,
  };
}

function countdownLabel(
  milliseconds: number | null,
  localize: ReturnType<typeof useI18n>["localize"],
): string {
  if (milliseconds === null) return localize("No individual deadline");
  const seconds = Math.ceil(milliseconds / 1000);
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  return `${hours.toString().padStart(2, "0")}:${minutes
    .toString()
    .padStart(2, "0")}:${rest.toString().padStart(2, "0")}`;
}

interface TerminalMutation {
  readonly action: "submit" | "abandon" | "retake";
  readonly key: string;
  readonly input:
    | SubmitRequest
    | { readonly expectedRevision: string }
    | { readonly locator?: string };
}

function completeAnswers(
  attempt: AttemptView,
  answers: Record<number, Answer>,
) {
  const entries = [];
  for (const question of attempt.questions) {
    const answer = answers[question.number];
    if (answer === undefined) return null;
    if (answer.type === "NUMERIC_FILL") {
      const value = normalizeNumericInput(answer.value ?? "");
      if (value !== null && !isValidNumericToken(value)) return null;
      entries.push({
        questionNumber: question.number,
        answer: { ...answer, value },
      });
    } else entries.push({ questionNumber: question.number, answer });
  }
  return entries;
}

export function AttemptScreen({
  attempt: initialAttempt,
  client,
  monitoring,
  monitoringContent,
}: {
  readonly attempt: AttemptView;
  readonly client: AttemptApiClient;
  readonly monitoring: AttemptMonitoringObserver;
  readonly monitoringContent?: React.ReactNode;
}) {
  const router = useRouter();
  const { t, localize, locale } = useI18n();
  const { user } = useAuth();
  const localizeRef = useLocalizeRef();
  const locatorContext = useAttemptEphemeralContext();
  const coordinator = useAttemptCoordinator({
    attempt: initialAttempt,
    client,
  });
  const { attempt } = coordinator;
  // Per-attempt review flags are client-side session state only: question
  // numbers in React state, never sent to the backend, never persisted to
  // browser storage, never touching answers, autosave or grading. Flags do
  // not survive a hard reload; that limitation is documented.
  const [flags, setFlags] = useState<ReadonlySet<number>>(() => new Set());
  const toggleFlag = useCallback((questionNumber: number) => {
    setFlags((current) => {
      const next = new Set(current);
      if (next.has(questionNumber)) next.delete(questionNumber);
      else next.add(questionNumber);
      return next;
    });
  }, []);
  // Current question tracks the navigator's current indicator; it is the
  // canonical question number, never an array index, so answer identity is
  // stable while scrolling and across locale changes.
  const [currentQuestion, setCurrentQuestion] = useState(
    attempt.questions[0]?.number ?? 1,
  );
  const [confirmation, setConfirmation] = useState<"submit" | "abandon" | null>(
    null,
  );
  const [terminalBusy, setTerminalBusy] = useState(false);
  const [attemptsExhausted, setAttemptsExhausted] = useState(false);
  const [terminalError, setTerminalError] = useState<string | null>(null);
  const [uncertain, setUncertain] = useState<TerminalMutation | null>(null);
  const [authorityPaused, setAuthorityPaused] = useState(false);
  const authorityRequest = useRef(0);
  useUnsavedNavigationGuard(
    uncertain !== null || terminalBusy,
    t("This action may not have finished. Leave anyway?"),
  );
  /** Leaving the focused workspace confirms first while a terminal mutation
   * outcome is unresolved; plain link navigation is guarded by the same hook. */
  function backFromAttempt() {
    if (
      (uncertain !== null || terminalBusy) &&
      !window.confirm(
        t(
          "This mutation outcome is unresolved and its exact recovery key exists only in memory. Leave anyway?",
        ),
      )
    )
      return;
    router.push("/app");
  }
  const { remaining, expired } = useInformativeCountdown(
    attempt,
    coordinator.refresh,
  );
  const optionalHistory = useOptionalObservations(
    attempt.publicationId,
    attempt.attemptId,
    attempt.state === "ACTIVE" &&
      !expired &&
      !authorityPaused &&
      !terminalBusy &&
      uncertain === null,
    currentQuestion,
  );
  const answered = Object.values(coordinator.answers).filter(
    (answer) => !isUnanswered(answer),
  ).length;
  const refreshAttempt = coordinator.refresh;
  const setCoordinatorMutationPaused = coordinator.setMutationPaused;

  const reconcileAuthority = useCallback(async () => {
    const request = ++authorityRequest.current;
    setAuthorityPaused(true);
    setCoordinatorMutationPaused(true);
    const result = await refreshAttempt();
    if (request !== authorityRequest.current) return;
    if (result.ok) {
      setCoordinatorMutationPaused(false);
      setAuthorityPaused(false);
      setTerminalError(null);
    } else {
      setTerminalError(
        localizeRef.current(
          "Status could not be refreshed. Actions remain paused. {message}",
          {
            message: attemptFailureMessage(result.error, localizeRef.current),
          },
        ),
      );
    }
  }, [refreshAttempt, setCoordinatorMutationPaused, localizeRef]);

  useEffect(() => {
    const offline = () => {
      authorityRequest.current += 1;
      setCoordinatorMutationPaused(true);
      setAuthorityPaused(true);
      setTerminalError(
        localizeRef.current(
          "You are offline. Actions are paused until the attempt status is refreshed.",
        ),
      );
    };
    const online = () => void reconcileAuthority();
    const visible = () => {
      if (document.visibilityState === "visible") void reconcileAuthority();
    };
    if (!navigator.onLine) offline();
    window.addEventListener("offline", offline);
    window.addEventListener("online", online);
    document.addEventListener("visibilitychange", visible);
    return () => {
      authorityRequest.current += 1;
      window.removeEventListener("offline", offline);
      window.removeEventListener("online", online);
      document.removeEventListener("visibilitychange", visible);
    };
  }, [reconcileAuthority, setCoordinatorMutationPaused, localizeRef]);

  // Best-effort realtime hints reconcile through the existing authoritative REST path;
  // hints never alter answers, grades, deadlines or completion state directly.
  const realtimeActive = attempt.state === "ACTIVE";
  const realtimeResources = useMemo<readonly RealtimeResource[]>(
    () =>
      realtimeActive
        ? [{ kind: "ATTEMPT_OWNER" as const, id: attempt.attemptId }]
        : [],
    [realtimeActive, attempt.attemptId],
  );
  const realtimeResync = useRealtimeResync({
    active: realtimeActive,
    run: reconcileAuthority,
    periodicRun: refreshAttempt,
  });
  const realtimeStatus = useRealtimeSubscriptions({
    enabled: realtimeActive,
    resources: realtimeResources,
    onSignal: realtimeResync.request,
  });

  useEffect(() => {
    if (expired) setCoordinatorMutationPaused(true);
  }, [expired, setCoordinatorMutationPaused]);

  useEffect(() => {
    if (!monitoring.enabled) return;
    monitoring.onAttemptLifecycle?.({
      attemptId: attempt.attemptId,
      state: attempt.state,
      collectionAllowed:
        attempt.state === "ACTIVE" &&
        !expired &&
        !authorityPaused &&
        !terminalBusy &&
        uncertain === null,
      selectedQuestionNumber: currentQuestion,
    });
  }, [
    attempt.attemptId,
    attempt.state,
    authorityPaused,
    expired,
    monitoring,
    currentQuestion,
    terminalBusy,
    uncertain,
  ]);

  /** Deliberate question selection (navigator jump): updates the current
   * indicator and reports the selection to the monitoring observer, exactly
   * like the previous per-question navigation clicks. */
  const selectQuestion = (questionNumber: number) => {
    setCurrentQuestion(questionNumber);
    if (monitoring.enabled)
      monitoring.onQuestionSelection?.({
        attemptId: attempt.attemptId,
        questionNumber,
      });
  };

  /** Scroll-position indicator update only: passive scrolling must not emit
   * monitoring selection events or shift layout. */
  const handleVisibleQuestionChange = useCallback((questionNumber: number) => {
    setCurrentQuestion((previous) =>
      previous === questionNumber ? previous : questionNumber,
    );
  }, []);

  const jumpToQuestion = (questionNumber: number) => {
    selectQuestion(questionNumber);
    scrollQuestionIntoView(questionNumber);
  };

  const handleAnswerChange = (questionNumber: number, answer: Answer) => {
    const previous = coordinator.answers[questionNumber];
    if (previous === undefined) return;
    coordinator.changeAnswer(questionNumber, answer);
    optionalHistory.queue.answer(questionNumber, previous, answer);
    if (!monitoring.enabled) return;
    monitoring.onAnswerInteraction?.({
      attemptId: attempt.attemptId,
      questionNumber,
      type: isUnanswered(answer)
        ? "cleared"
        : isUnanswered(previous)
          ? "selected"
          : "changed",
    });
  };

  const mutationsBlocked = authorityPaused || expired;

  const runTerminal = useCallback(
    async (intent: TerminalMutation) => {
      if (mutationsBlocked) {
        setTerminalError(
          localizeRef.current(
            "Actions are paused until the attempt status is refreshed.",
          ),
        );
        return;
      }
      setTerminalBusy(true);
      setTerminalError(null);
      let result;
      if (intent.action === "submit")
        result = await client.submit(
          attempt.attemptId,
          intent.input as SubmitRequest,
          intent.key,
        );
      else if (intent.action === "abandon")
        result = await client.abandon(
          attempt.attemptId,
          intent.input as { expectedRevision: string },
          intent.key,
        );
      else
        result = await client.retake(
          attempt.publicationId,
          intent.input as { locator?: string },
          intent.key,
        );
      setTerminalBusy(false);
      if (!result.ok) {
        if (
          intent.action === "retake" &&
          result.error.kind === "api" &&
          result.error.code === "ATTEMPTS_EXHAUSTED"
        )
          setAttemptsExhausted(true);
        if (isUncertainAttemptMutation(result.error)) setUncertain(intent);
        else setUncertain(null);
        setTerminalError(
          attemptFailureMessage(result.error, localizeRef.current),
        );
        if (
          result.error.kind === "api" &&
          result.error.code === "REVISION_CONFLICT"
        ) {
          const fresh = await coordinator.refreshPreservingDrafts();
          setTerminalError(
            fresh.ok
              ? localizeRef.current(
                  "This attempt changed elsewhere. Your answers are preserved; resolve the highlighted conflicts, then confirm the action again.",
                )
              : localizeRef.current(
                  "The attempt changed and refreshing failed. {message}",
                  {
                    message: attemptFailureMessage(
                      fresh.error,
                      localizeRef.current,
                    ),
                  },
                ),
          );
          return;
        }
        if (
          result.error.kind === "api" &&
          [
            "ATTEMPT_TERMINAL",
            "ATTEMPT_DEADLINE_REACHED",
            "CLOSURE_CUTOFF_REACHED",
          ].includes(result.error.code)
        )
          await coordinator.refresh();
        return;
      }
      setUncertain(null);
      if (intent.action === "submit")
        router.push(`/app/attempts/${attempt.attemptId}/result`);
      else if (intent.action === "abandon") await coordinator.refresh();
      else router.replace(`/app/attempts/${result.value.attemptId}`);
    },
    [
      attempt.attemptId,
      attempt.publicationId,
      client,
      coordinator,
      localizeRef,
      mutationsBlocked,
      router,
    ],
  );

  const submit = async () => {
    setConfirmation(null);
    if (mutationsBlocked) {
      setTerminalError(
        localizeRef.current(
          "Submitting is paused until the attempt status is refreshed.",
        ),
      );
      return;
    }
    const snapshot = await coordinator.flushOrdinarySaves();
    if (snapshot === null) {
      setTerminalError(
        localizeRef.current(
          "Resolve every failed, conflicting, or pending save before submission.",
        ),
      );
      return;
    }
    const answers = completeAnswers(snapshot.attempt, snapshot.answers);
    if (answers === null) {
      setTerminalError(
        localizeRef.current(
          "One or more answers do not match the required schema.",
        ),
      );
      return;
    }
    const input: SubmitRequest = {
      expectedRevision: snapshot.attempt.revision,
      answers,
    };
    if (
      new TextEncoder().encode(JSON.stringify(input)).byteLength > 2_097_152
    ) {
      setTerminalError(
        localizeRef.current(
          "Your answers are too large to submit. Nothing was cut or sent.",
        ),
      );
      return;
    }
    await runTerminal({ action: "submit", input, key: newIdempotencyKey() });
  };

  const abandon = async () => {
    setConfirmation(null);
    if (mutationsBlocked) {
      setTerminalError(
        localizeRef.current(
          "Abandoning is paused until the attempt status is refreshed.",
        ),
      );
      return;
    }
    const snapshot = await coordinator.flushOrdinarySaves();
    if (snapshot === null) {
      setTerminalError(
        localizeRef.current(
          "Resolve every pending save before abandoning so its outcome is explicit.",
        ),
      );
      return;
    }
    await runTerminal({
      action: "abandon",
      input: { expectedRevision: snapshot.attempt.revision },
      key: newIdempotencyKey(),
    });
  };

  const retake = () => {
    if (terminalBusy || uncertain !== null || attemptsExhausted) return;
    if (mutationsBlocked) {
      setTerminalError(
        localizeRef.current(
          "Retaking is paused until the attempt status is refreshed.",
        ),
      );
      return;
    }
    const locator = locatorContext.getPublicLocator(attempt.publicationId);
    void runTerminal({
      action: "retake",
      input: locator === null ? {} : { locator },
      key: newIdempotencyKey(),
    });
  };

  const reconcileTerminal = async () => {
    if (uncertain?.action === "retake") {
      setTerminalError(
        localizeRef.current(
          "Retry the pending action first to start a retake.",
        ),
      );
      return;
    }
    const fresh = await coordinator.refresh();
    if (!fresh.ok) {
      setTerminalError(attemptFailureMessage(fresh.error, localizeRef.current));
      return;
    }
    if (fresh.value.state === "SUBMITTED")
      router.push(`/app/attempts/${fresh.value.attemptId}/result`);
    else if (fresh.value.state === "ABANDONED") setUncertain(null);
    else
      setTerminalError(
        localizeRef.current(
          "The attempt is still active. Retry the pending action to resolve it.",
        ),
      );
  };

  if (attempt.questions.length === 0) return null;

  return (
    <div className="flex min-h-dvh min-w-0 flex-col">
      <AttemptWorkspaceHeader
        actionsDisabled={mutationsBlocked || uncertain !== null || terminalBusy}
        answeredCount={answered}
        onAbandon={() => setConfirmation("abandon")}
        onBack={backFromAttempt}
        onSubmit={() => setConfirmation("submit")}
        realtime={<RealtimeStatusInline status={realtimeStatus} />}
        remainingLabel={
          remaining === null ? null : countdownLabel(remaining, localize)
        }
        savePhase={aggregateSavePhase(coordinator.statuses)}
        showActions={attempt.state === "ACTIVE"}
        studentName={user?.username ?? null}
        title={
          locatorContext.getPublicationTitle(attempt.publicationId) ??
          t("Assessment")
        }
        totalCount={attempt.questions.length}
      />
      <div className="w-full flex-1 space-y-5 px-4 py-6 sm:px-6 lg:px-8">
        {attempt.deadlineAt ? (
          <p className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-foreground-muted">
            <span>
              {t("Deadline {deadline}", {
                deadline: formatInstant(locale, attempt.deadlineAt, {
                  withTimeZone: true,
                }),
              })}
            </span>
          </p>
        ) : null}

        {attempt.closure?.closureCutoffAt ? (
          <Alert
            title={t("Teacher closure cutoff is active")}
            variant="warning"
          >
            {t(
              "The cutoff is {cutoff}. Only saved answers count if the attempt is finalized.",
              {
                cutoff: formatInstant(locale, attempt.closure.closureCutoffAt, {
                  withTimeZone: true,
                }),
              },
            )}
          </Alert>
        ) : null}
        {monitoringContent}
        {optionalHistory.content}
        {expired && attempt.state === "ACTIVE" ? (
          <Alert title={t("Checking status")} variant="warning">
            {t(
              "Time is up. Editing is paused while the final state is confirmed.",
            )}
            <div className="mt-3">
              <Button
                onClick={() => void reconcileAuthority()}
                size="compact"
                variant="secondary"
              >
                {t("Retry status check")}
              </Button>
            </div>
          </Alert>
        ) : null}
        {terminalError ? (
          <Alert title={t("Action could not be confirmed")} variant="danger">
            {terminalError}
          </Alert>
        ) : null}
        {authorityPaused ? (
          <Alert title={t("Status check required")} variant="warning">
            {t(
              "Answers and final actions stay paused until the current state is confirmed.",
            )}
            <div className="mt-3">
              <Button
                onClick={() => void reconcileAuthority()}
                size="compact"
                variant="secondary"
              >
                {t("Retry status check")}
              </Button>
            </div>
          </Alert>
        ) : null}
        {uncertain ? (
          <Alert
            title={t("{action} outcome is uncertain", {
              action: uncertain.action,
            })}
            variant="warning"
          >
            {t("Your original request is kept in memory.")}
            <div className="mt-3 flex flex-wrap gap-2">
              <Button
                disabled={mutationsBlocked}
                isLoading={terminalBusy}
                onClick={() => void runTerminal(uncertain)}
                size="compact"
                variant="secondary"
              >
                {t("Retry exact operation")}
              </Button>
              {uncertain.action !== "retake" ? (
                <Button
                  disabled={mutationsBlocked}
                  onClick={() => void reconcileTerminal()}
                  size="compact"
                  variant="ghost"
                >
                  {t("Check current state")}
                </Button>
              ) : null}
            </div>
          </Alert>
        ) : null}

        {attempt.state !== "ACTIVE" ? (
          <Surface className="p-6 sm:p-8">
            <h2 className="text-2xl font-bold">
              {attempt.state === "SUBMITTED"
                ? t("Attempt submitted")
                : t("Attempt abandoned")}
            </h2>
            <p className="mt-3 leading-7 text-foreground-secondary">
              {t("This attempt has ended. Answers can no longer be changed.")}
            </p>
            <div className="mt-6 flex flex-wrap gap-3">
              {attempt.state === "SUBMITTED" ? (
                <Link
                  className={PRIMARY_LINK_CLASSES}
                  href={`/app/attempts/${attempt.attemptId}/result`}
                >
                  {t("View result")}
                </Link>
              ) : null}
              <Button
                disabled={
                  mutationsBlocked || uncertain !== null || attemptsExhausted
                }
                isLoading={terminalBusy}
                onClick={retake}
                variant="secondary"
              >
                {t("Start explicit retake")}
              </Button>
              <Link
                className={OUTLINE_LINK_CLASSES}
                href="/app/assessments/join"
              >
                {t("Enter another assessment")}
              </Link>
            </div>
            {attemptsExhausted ? (
              <p
                className="mt-4 text-sm text-foreground-secondary"
                role="status"
              >
                {t("No more attempts are available for this assessment.")}
              </p>
            ) : null}
            {locatorContext.getPublicLocator(attempt.publicationId) === null ? (
              <p className="mt-4 text-xs text-foreground-muted">
                {t(
                  "For a PUBLIC assessment after a reload, enter its private locator again before retaking. The locator is never saved.",
                )}
              </p>
            ) : null}
          </Surface>
        ) : (
          <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_18rem] lg:items-start">
            {/* DOM order keeps the navigator reachable first on narrow
              screens; the desktop grid places it beside the list. */}
            <div className="min-w-0 lg:col-start-2 lg:row-start-1">
              <AttemptQuestionNavigator
                answers={coordinator.answers}
                currentNumber={currentQuestion}
                flags={flags}
                onJump={jumpToQuestion}
                questions={attempt.questions}
                statuses={coordinator.statuses}
              />
            </div>
            <div className="min-w-0 lg:col-start-1 lg:row-start-1">
              <AttemptQuestionList
                actionsDisabled={mutationsBlocked}
                answers={coordinator.answers}
                answersDisabled={
                  mutationsBlocked || terminalBusy || uncertain !== null
                }
                flags={flags}
                onAnswerChange={handleAnswerChange}
                onCheckAuthoritative={(questionNumber) =>
                  void coordinator.checkAuthoritativeState(questionNumber)
                }
                onRetryFailed={(questionNumber) =>
                  void (coordinator.statuses[questionNumber]?.phase ===
                  "conflict"
                    ? coordinator.saveConflict(questionNumber)
                    : coordinator.retryFailed(questionNumber))
                }
                onRetryUncertain={(questionNumber) =>
                  void coordinator.retryUncertain(questionNumber)
                }
                onToggleFlag={toggleFlag}
                onVisibleQuestionChange={handleVisibleQuestionChange}
                questions={attempt.questions}
                statuses={coordinator.statuses}
              />
            </div>
          </div>
        )}

        {attempt.state === "ACTIVE" ? (
          <p className="max-w-3xl text-xs leading-5 text-foreground-muted">
            {t(
              "Submitting sends all your answers. Abandoning is irreversible and leaves no result.",
            )}
          </p>
        ) : null}

        {confirmation === "submit" ? (
          <PublicationConfirmation
            confirmLabel={t("Submit complete answers")}
            onCancel={() => setConfirmation(null)}
            onConfirm={() => void submit()}
            title={t("Submit this Attempt?")}
          >
            <p>
              {t(
                "Quizopia will save pending drafts, then submit one answer per question. Your grade is calculated right after.",
              )}
            </p>
          </PublicationConfirmation>
        ) : null}
        {confirmation === "abandon" ? (
          <PublicationConfirmation
            confirmLabel={t("Abandon Attempt")}
            destructive
            onCancel={() => setConfirmation(null)}
            onConfirm={() => void abandon()}
            title={t("Abandon this Attempt?")}
          >
            <p>
              {t(
                "Abandoning is irreversible, uses up this attempt, and creates no result. Closing the browser never abandons for you.",
              )}
            </p>
          </PublicationConfirmation>
        ) : null}
      </div>
    </div>
  );
}

export function StudentResultPage({
  attemptId,
}: {
  readonly attemptId: string;
}) {
  const client = useAttemptClient();
  const { getSessionGeneration, user } = useAuth();
  const scope = `${user?.id ?? "none"}:${getSessionGeneration() ?? "none"}:${attemptId}`;
  return (
    <StudentPageBoundary>
      {client === null ? (
        <ClientUnavailable />
      ) : (
        <ResultScreen attemptId={attemptId} client={client} key={scope} />
      )}
    </StudentPageBoundary>
  );
}

export function ResultScreen({
  attemptId,
  client,
}: {
  readonly attemptId: string;
  readonly client: AttemptApiClient;
}) {
  const { t, locale } = useI18n();
  const localizeRef = useLocalizeRef();
  const [result, setResult] = useState<Result | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [aggregate, setAggregate] = useState<Result | null | undefined>(
    undefined,
  );
  useEffect(() => {
    const controller = new AbortController();
    void client
      .getResult(attemptId, controller.signal)
      .then(async (response) => {
        if (controller.signal.aborted) return;
        if (!response.ok) {
          setError(attemptFailureMessage(response.error, localizeRef.current));
          return;
        }
        setResult(response.value);
        const best = await client.getAggregate(
          response.value.publicationId,
          controller.signal,
        );
        if (!controller.signal.aborted)
          setAggregate(best.ok ? best.value.bestResult : undefined);
      });
    return () => controller.abort();
  }, [attemptId, client, localizeRef]);
  if (error)
    return (
      <Alert title={t("Result unavailable")} variant="warning">
        {error}
      </Alert>
    );
  if (result === null) return <AttemptLoading />;
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div>
        <p className="text-xs font-bold uppercase tracking-[0.14em] text-primary">
          {t("Official result")}
        </p>
        <h1 className="mt-2 font-heading text-3xl font-extrabold">
          {t("Attempt result")}
        </h1>
      </div>
      <Surface className="overflow-hidden p-6 sm:p-8">
        <p className="text-sm font-semibold text-foreground-muted">
          {t("Total score")}
        </p>
        <p className="mt-2 font-heading text-5xl font-extrabold text-primary">
          {formatDecimal(locale, result.percentageDisplay)}%
        </p>
        <p className="mt-3 text-lg font-semibold">
          {t("{earned} of {possible} exact weight", {
            earned: formatDecimal(locale, result.earnedWeight),
            possible: formatDecimal(locale, result.possibleWeight),
          })}
        </p>
        <dl className="mt-7 grid gap-4 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-foreground-muted">{t("Attempt")}</dt>
            <dd className="mt-1 font-semibold">{result.ordinal}</dd>
          </div>
          <div>
            <dt className="text-foreground-muted">{t("Terminal reason")}</dt>
            <dd className="mt-1 font-semibold">
              {result.terminalReason.replaceAll("_", " ")}
            </dd>
          </div>
          <div>
            <dt className="text-foreground-muted">{t("Finalized")}</dt>
            <dd className="mt-1 font-semibold">
              {formatInstant(locale, result.finalizedAt)}
            </dd>
          </div>
          <div>
            <dt className="text-foreground-muted">{t("Grading policy")}</dt>
            <dd className="mt-1 font-mono text-xs">
              {result.gradingPolicyId} · v{result.gradingPolicyVersion}
            </dd>
          </div>
        </dl>
      </Surface>
      {aggregate !== undefined ? (
        <Alert title={t("Best successfully graded Attempt")} variant="info">
          {aggregate === null
            ? t("No aggregate score is available.")
            : t("Attempt {ordinal}: {percentage}% ({earned}/{possible}).", {
                ordinal: aggregate.ordinal,
                percentage: formatDecimal(locale, aggregate.percentageDisplay),
                earned: formatDecimal(locale, aggregate.earnedWeight),
                possible: formatDecimal(locale, aggregate.possibleWeight),
              })}
        </Alert>
      ) : null}
      <Alert title={t("Answer details remain protected")} variant="info">
        {t(
          "Correct answers and explanations unlock in the review page after the assessment closes.",
        )}
      </Alert>
      <div className="flex flex-wrap gap-3">
        <Link
          className={PRIMARY_LINK_CLASSES}
          href={`/app/attempts/${attemptId}/review`}
        >
          {t("Request answer review")}
        </Link>
        <Link
          className={OUTLINE_LINK_CLASSES}
          href={`/app/attempts/${attemptId}`}
        >
          {t("Back to Attempt")}
        </Link>
      </div>
    </div>
  );
}

export function StudentReviewPage({
  attemptId,
}: {
  readonly attemptId: string;
}) {
  const client = useAttemptClient();
  const { getSessionGeneration, user } = useAuth();
  const scope = `${user?.id ?? "none"}:${getSessionGeneration() ?? "none"}:${attemptId}`;
  return (
    <StudentPageBoundary>
      {client === null ? (
        <ClientUnavailable />
      ) : (
        <ReviewScreen attemptId={attemptId} client={client} key={scope} />
      )}
    </StudentPageBoundary>
  );
}

export function ReviewScreen({
  attemptId,
  client,
}: {
  readonly attemptId: string;
  readonly client: AttemptApiClient;
}) {
  const { t, localize, locale } = useI18n();
  const [review, setReview] = useState<Review | null>(null);
  const [failure, setFailure] = useState<AttemptFailure | null>(null);
  const load = useCallback(() => {
    const controller = new AbortController();
    void client.getReview(attemptId, controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      if (result.ok) setReview(result.value);
      else setFailure(result.error);
    });
    return () => controller.abort();
  }, [attemptId, client]);
  useEffect(load, [load]);
  if (failure)
    return (
      <div className="mx-auto max-w-3xl space-y-5">
        <Alert title={t("Answer review unavailable")} variant="warning">
          {localize(
            "{message} No answer review was requested before opening this page.",
            { message: attemptFailureMessage(failure, localize) },
          )}
        </Alert>
        <Link
          className={OUTLINE_LINK_CLASSES}
          href={`/app/attempts/${attemptId}/result`}
        >
          {t("Back to result")}
        </Link>
      </div>
    );
  if (review === null) return <AttemptLoading />;
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div>
        <p className="text-xs font-bold uppercase tracking-[0.14em] text-success">
          {t("Authorized closed review")}
        </p>
        <h1 className="mt-2 font-heading text-3xl font-extrabold">
          {t("Answer review")}
        </h1>
        <p className="mt-3 text-foreground-secondary">
          {t(
            "These details are available because you submitted and the assessment has closed.",
          )}
        </p>
      </div>
      {review.questions.map((item) => (
        <Surface className="p-6 sm:p-8" key={item.question.number}>
          <p className="text-xs font-bold uppercase tracking-wide text-primary">
            {t("Question {number} · {earned}/{possible}", {
              number: item.question.number,
              earned: formatDecimal(locale, item.earnedWeight),
              possible: formatDecimal(locale, item.possibleWeight),
            })}
          </p>
          <h2 className="mt-3 whitespace-pre-wrap text-lg font-bold leading-8">
            {item.question.stemMarkdown}
          </h2>
          <dl className="mt-5 space-y-4 text-sm">
            <div className="rounded-xl border border-border bg-background p-4">
              <dt className="font-semibold text-foreground-muted">
                {t("Your answer")}
              </dt>
              <dd className="mt-1 text-foreground">
                <AnswerSummary answer={item.answer} />
              </dd>
            </div>
            <div className="rounded-xl border border-success/30 bg-success/5 p-4">
              <dt className="font-semibold text-foreground-muted">
                {t("Correct answer")}
              </dt>
              <dd className="mt-1 text-foreground">
                <AnswerSummary answer={item.correctAnswer} />
              </dd>
            </div>
          </dl>
          {item.explanationMarkdown ? (
            <div className="mt-5 border-l-4 border-primary pl-4">
              <p className="text-sm font-semibold">{t("Explanation")}</p>
              <p className="mt-1 whitespace-pre-wrap text-sm leading-6 text-foreground-secondary">
                {item.explanationMarkdown}
              </p>
            </div>
          ) : null}
        </Surface>
      ))}
      <Link
        className={OUTLINE_LINK_CLASSES}
        href={`/app/attempts/${attemptId}/result`}
      >
        {t("Back to result")}
      </Link>
    </div>
  );
}
