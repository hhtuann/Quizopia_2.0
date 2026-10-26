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
import { StateBadge } from "../../../components/ui/state-badge";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
import {
  formatDateTime,
  formatDecimal,
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
  ATTEMPT_QUESTION_PAGE_SIZE,
  boundedQuestionPage,
  canonicalUuid,
  effectiveAttemptDeadline,
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
import {
  AnswerField,
  AnswerSummary,
  SaveStatus,
  savePhaseLabel,
} from "./answer-fields";

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

/** Semantic StateBadge variants for server-returned Attempt states. */
const attemptStateVariants: Record<
  "ACTIVE" | "SUBMITTED" | "ABANDONED",
  "info" | "success" | "warning" | "neutral"
> = {
  ACTIVE: "info",
  SUBMITTED: "success",
  ABANDONED: "neutral",
};

function StudentPageBoundary({
  children,
}: {
  readonly children: React.ReactNode;
}) {
  return (
    <AuthenticatedBoundary>
      <StudentWorkspace>{children}</StudentWorkspace>
    </AuthenticatedBoundary>
  );
}

function StudentWorkspace({
  children,
}: {
  readonly children: React.ReactNode;
}) {
  const { activeWorkspace, switchWorkspace, user } = useAuth();
  const { t } = useI18n();
  return (
    <ApplicationShell currentSection="assessments">
      {!user?.roles.includes("STUDENT") ? (
        <Alert title={t("Student access required")} variant="warning">
          {t(
            "An authenticated account with current Student authority is required. The frontend does not verify admission or class membership itself.",
          )}
        </Alert>
      ) : activeWorkspace !== "LEARNING" ? (
        <Surface className="mx-auto max-w-2xl p-6 sm:p-8">
          <h1 className="font-heading text-2xl font-bold">
            {t("Open the Learning workspace")}
          </h1>
          <p className="mt-3 leading-7 text-foreground-secondary">
            {t(
              "Switch workspace to organize the Student experience. The Gateway and Assessment Service still authorize every operation.",
            )}
          </p>
          <Button className="mt-5" onClick={() => switchWorkspace("LEARNING")}>
            {t("Switch to Learning")}
          </Button>
        </Surface>
      ) : (
        children
      )}
    </ApplicationShell>
  );
}

function ClientUnavailable() {
  const { t } = useI18n();
  return (
    <Alert title={t("Assessment access unavailable")} variant="warning">
      {t(
        "The authenticated request service is unavailable. Sign in again before continuing.",
      )}
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
        ? t("Unlimited; server closure rules still apply")
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
              {formatDateTime(locale, publication.startAt)}
            </time>
          </dd>
        </div>
        <div className="rounded-xl border border-border bg-background p-4">
          <dt className="text-xs font-semibold uppercase tracking-wide text-foreground-muted">
            {t("Entry window ends")}
          </dt>
          <dd className="mt-1 text-sm text-foreground">
            <time dateTime={publication.endAt}>
              {formatDateTime(locale, publication.endAt)}
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
  const { rememberPublicLocator } = useAttemptEphemeralContext();
  const [mode, setMode] = useState<EntryMode>("PUBLIC");
  const [locator, setLocator] = useState("");
  const [publicationId, setPublicationId] = useState("");
  const [preview, setPreview] = useState<StudentView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"entry" | "start" | null>(null);
  const [uncertain, setUncertain] = useState<CreationIntent | null>(null);
  useUnsavedNavigationGuard(
    uncertain !== null || busy === "start",
    t(
      "This start outcome is unresolved and its exact recovery key exists only in memory. Leave anyway?",
    ),
  );

  const enter = async (event: FormEvent) => {
    event.preventDefault();
    if (uncertain !== null) {
      setError(
        localizeRef.current(
          "Resolve the uncertain start with its retained exact request before checking another assessment.",
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
            : "Enter a canonical lowercase Publication UUID.",
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
          {t(
            "Public assessments still require a signed-in Student. Class access is checked against current authoritative membership.",
          )}
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
                "The locator stays in temporary application memory and is sent only in the authorized request body.",
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
              helperText={t(
                "Class browsing is not available. Ask your instructor for the canonical Publication ID.",
              )}
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
                "Server-provided availability is {status}. A browser clock does not grant entry.",
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
            "The original body and Idempotency-Key are retained in memory. Retry the same request; do not create a new start intent.",
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

function AttemptLoading() {
  const { t } = useI18n();
  return (
    <Surface className="mx-auto max-w-3xl p-8">
      <LoadingIndicator label={t("Loading authoritative attempt")} />
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
    <StudentPageBoundary>
      {client === null ? (
        <ClientUnavailable />
      ) : (
        <AttemptLoader
          attemptId={attemptId}
          client={client}
          key={scope}
          monitoring={monitoring}
        />
      )}
    </StudentPageBoundary>
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
      <Alert title={t("Attempt unavailable")} variant="danger">
        {invalidAttemptId
          ? t("The Attempt ID is invalid.")
          : (error ?? undefined)}
      </Alert>
    );
  if (attempt === null) return <AttemptLoading />;
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
          "Assessment work is available while the authorized CLASS projection is checked. No activity or camera collection has started.",
        )}
      </Alert>
    );
  if (projection.status === "unavailable")
    return (
      <Alert title={t("Monitoring authority unavailable")} variant="warning">
        {t(
          "The current monitoring requirement could not be verified, so activity and camera collection remain off. Assessment saves and submission stay independent.",
        )}
      </Alert>
    );
  if (state.status === "active")
    return (
      <div className="space-y-5">
        <Alert title={t("Activity monitoring active")} variant="info">
          {t(
            "Fresh server authorization confirmed this bounded CLASS Attempt. Browser evidence contains interaction metadata only and is not proof of misconduct. Assessment SAVE and SUBMIT do not wait for Proctoring.",
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
      {t(
        "Current backend authority is being verified before any browser observer or camera control becomes active.",
      )}
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
  const localizeRef = useLocalizeRef();
  const locatorContext = useAttemptEphemeralContext();
  const coordinator = useAttemptCoordinator({
    attempt: initialAttempt,
    client,
  });
  const { attempt } = coordinator;
  const [selected, setSelected] = useState(attempt.questions[0]?.number ?? 1);
  const [questionPage, setQuestionPage] = useState(0);
  const [confirmation, setConfirmation] = useState<"submit" | "abandon" | null>(
    null,
  );
  const [terminalBusy, setTerminalBusy] = useState(false);
  const [terminalError, setTerminalError] = useState<string | null>(null);
  const [uncertain, setUncertain] = useState<TerminalMutation | null>(null);
  const [authorityPaused, setAuthorityPaused] = useState(false);
  const authorityRequest = useRef(0);
  useUnsavedNavigationGuard(
    uncertain !== null || terminalBusy,
    t(
      "This mutation outcome is unresolved and its exact recovery key exists only in memory. Leave anyway?",
    ),
  );
  const { remaining, expired } = useInformativeCountdown(
    attempt,
    coordinator.refresh,
  );
  const question =
    attempt.questions.find((item) => item.number === selected) ??
    attempt.questions[0];
  const questionBounds = boundedQuestionPage(
    attempt.questions.length,
    questionPage,
  );
  const visibleQuestions = attempt.questions.slice(
    questionBounds.start,
    questionBounds.end,
  );
  const questionPageCount = Math.max(
    1,
    Math.ceil(attempt.questions.length / ATTEMPT_QUESTION_PAGE_SIZE),
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
          "Authoritative status could not be refreshed. Actions remain paused. {message}",
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
          "The browser is offline. Actions are paused until authoritative Attempt status is refreshed.",
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
      selectedQuestionNumber: selected,
    });
  }, [
    attempt.attemptId,
    attempt.state,
    authorityPaused,
    expired,
    monitoring,
    selected,
    terminalBusy,
    uncertain,
  ]);

  const selectQuestion = (questionNumber: number) => {
    setSelected(questionNumber);
    if (monitoring.enabled)
      monitoring.onQuestionSelection?.({
        attemptId: attempt.attemptId,
        questionNumber,
      });
  };

  const selectQuestionPage = (page: number) => {
    const next = boundedQuestionPage(attempt.questions.length, page);
    setQuestionPage(next.page);
    const first = attempt.questions[next.start];
    if (first !== undefined) selectQuestion(first.number);
  };
  const mutationsBlocked = authorityPaused || expired;

  const runTerminal = useCallback(
    async (intent: TerminalMutation) => {
      if (mutationsBlocked) {
        setTerminalError(
          localizeRef.current(
            "Actions are paused until authoritative Attempt status is refreshed.",
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
                  "The Attempt changed on the server. Your complete local answer set is preserved; resolve any highlighted conflicts, then confirm the action again.",
                )
              : localizeRef.current(
                  "The Attempt revision changed, and authoritative refresh failed. {message}",
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
          "Submission is paused until authoritative Attempt status is refreshed.",
        ),
      );
      return;
    }
    const snapshot = await coordinator.flushOrdinarySaves();
    if (snapshot === null) {
      setTerminalError(
        localizeRef.current(
          "Resolve every failed, conflicting, or uncertain save before submission.",
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
          "The complete answer set exceeds the server body limit. Nothing was truncated or submitted.",
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
          "Abandon is paused until authoritative Attempt status is refreshed.",
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
    if (mutationsBlocked) {
      setTerminalError(
        localizeRef.current(
          "Retake is paused until authoritative Attempt status is refreshed.",
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
          "A retake cannot be discovered from the old Attempt. Retry the retained exact operation to resolve its outcome.",
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
          "Authoritative state is still ACTIVE. Retry the retained operation to resolve its outcome.",
        ),
      );
  };

  if (question === undefined) return null;

  return (
    <div className="space-y-5">
      <header className="rounded-2xl border border-primary/10 bg-surface p-5 shadow-card sm:p-6">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="text-xs font-bold uppercase tracking-[0.14em] text-primary">
              {t("Attempt {ordinal}", { ordinal: attempt.ordinal })}
            </p>
            <h1 className="mt-2 font-heading text-2xl font-bold sm:text-3xl">
              {t("Student assessment")}
            </h1>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <StateBadge variant={attemptStateVariants[attempt.state]}>
                {attempt.state}
              </StateBadge>
              <span className="font-mono text-xs text-foreground-muted">
                {t("Attempt {attemptId}", { attemptId: attempt.attemptId })}
              </span>
            </div>
          </div>
          <div className="rounded-xl border border-border bg-background px-4 py-3 text-right">
            <p className="text-xs font-semibold uppercase tracking-wide text-foreground-muted">
              {t("Informative countdown")}
            </p>
            <p
              className="mt-1 font-mono text-xl font-bold text-foreground"
              role="timer"
            >
              {countdownLabel(remaining, localize)}
            </p>
            <p className="mt-1 text-xs text-foreground-muted">
              {t("Server time and refreshed status are authoritative.")}
            </p>
          </div>
        </div>
        <div className="mt-5 flex flex-wrap gap-3 text-sm text-foreground-secondary">
          <span>
            {t("{answered} of {total} answered", {
              answered: formatNumber(locale, answered),
              total: formatNumber(locale, attempt.questions.length),
            })}
          </span>
          <span aria-hidden="true">·</span>
          <span>
            {t("Revision {revision}", { revision: attempt.revision })}
          </span>
          {attempt.deadlineAt ? (
            <>
              <span aria-hidden="true">·</span>
              <span>
                {t("Deadline {deadline}", {
                  deadline: formatDateTime(locale, attempt.deadlineAt),
                })}
              </span>
            </>
          ) : null}
          <span aria-hidden="true">·</span>
          <RealtimeStatusInline status={realtimeStatus} />
        </div>
      </header>

      {attempt.closure?.closureCutoffAt ? (
        <Alert title={t("Teacher closure cutoff is active")} variant="warning">
          {t(
            "The authoritative cutoff is {cutoff}. Only committed answers are included if the server finalizes the attempt.",
            {
              cutoff: formatDateTime(locale, attempt.closure.closureCutoffAt),
            },
          )}
        </Alert>
      ) : null}
      {monitoringContent}
      {expired && attempt.state === "ACTIVE" ? (
        <Alert title={t("Checking authoritative status")} variant="warning">
          {t(
            "The local countdown reached zero. Editing is paused while the Attempt is reconciled with the server.",
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
        <Alert
          title={t("Authoritative reconciliation required")}
          variant="warning"
        >
          {t(
            "Answer and terminal actions remain paused until current server state is confirmed.",
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
          {t("The original body and Idempotency-Key remain in memory.")}
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
                {t("Check authoritative state")}
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
            {t(
              "This terminal state was read from the server. Answers can no longer be changed.",
            )}
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
              disabled={mutationsBlocked}
              isLoading={terminalBusy}
              onClick={retake}
              variant="secondary"
            >
              {t("Start explicit retake")}
            </Button>
            <Link className={OUTLINE_LINK_CLASSES} href="/app/assessments/join">
              {t("Enter another assessment")}
            </Link>
          </div>
          {locatorContext.getPublicLocator(attempt.publicationId) === null ? (
            <p className="mt-4 text-xs text-foreground-muted">
              {t(
                "If this is a PUBLIC assessment and the page was reloaded, enter its private locator again before retaking. The locator is never persisted.",
              )}
            </p>
          ) : null}
        </Surface>
      ) : (
        <div className="grid gap-5 lg:grid-cols-[minmax(0,15rem)_minmax(0,1fr)]">
          <nav
            aria-label={t("Attempt questions")}
            className="lg:sticky lg:top-4 lg:self-start"
          >
            <Surface className="p-4">
              <p className="px-2 text-sm font-bold text-foreground">
                {t("Questions")}
              </p>
              <p
                aria-live="polite"
                className="mt-1 px-2 text-xs text-foreground-muted"
              >
                {t("{start}–{end} of {total}", {
                  start: formatNumber(locale, questionBounds.start + 1),
                  end: formatNumber(locale, questionBounds.end),
                  total: formatNumber(locale, attempt.questions.length),
                })}
              </p>
              <ol className="mt-3 grid grid-cols-4 gap-2 sm:grid-cols-8 lg:grid-cols-4">
                {visibleQuestions.map((item) => {
                  const status =
                    coordinator.statuses[item.number]?.phase ?? "draft";
                  return (
                    <li key={item.number}>
                      <button
                        aria-current={
                          selected === item.number ? "step" : undefined
                        }
                        aria-label={t("Question {number}, {status}", {
                          number: item.number,
                          status: savePhaseLabel(status, t),
                        })}
                        className={`flex min-h-11 w-full items-center justify-center rounded-lg border font-mono text-sm font-bold focus-visible:ring-2 focus-visible:ring-focus ${
                          selected === item.number
                            ? "border-primary bg-primary text-white"
                            : status === "saved"
                              ? "border-success/40 bg-success/5 text-foreground"
                              : "border-warning/50 bg-warning/5 text-foreground"
                        }`}
                        onClick={() => selectQuestion(item.number)}
                        type="button"
                      >
                        {item.number}
                      </button>
                    </li>
                  );
                })}
              </ol>
              {questionPageCount > 1 ? (
                <div className="mt-4 grid grid-cols-2 gap-2">
                  <Button
                    disabled={questionBounds.page === 0}
                    onClick={() => selectQuestionPage(questionBounds.page - 1)}
                    size="compact"
                    variant="ghost"
                  >
                    {t("Previous questions")}
                  </Button>
                  <Button
                    disabled={questionBounds.page === questionPageCount - 1}
                    onClick={() => selectQuestionPage(questionBounds.page + 1)}
                    size="compact"
                    variant="ghost"
                  >
                    {t("Next questions")}
                  </Button>
                </div>
              ) : null}
            </Surface>
          </nav>

          <Surface
            className="min-w-0 p-5 sm:p-8"
            role="region"
            aria-labelledby={`question-${question.number}`}
          >
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <p className="text-xs font-bold uppercase tracking-wide text-primary">
                  {t("Question {number} · {type}", {
                    number: question.number,
                    type: question.type.replaceAll("_", " "),
                  })}
                </p>
                <h2
                  className="mt-3 whitespace-pre-wrap text-lg font-bold leading-8"
                  id={`question-${question.number}`}
                >
                  {question.stemMarkdown}
                </h2>
              </div>
              <SaveStatus
                status={
                  coordinator.statuses[question.number] ?? { phase: "draft" }
                }
              />
            </div>
            <div className="mt-6">
              <AnswerField
                answer={coordinator.answers[question.number]!}
                disabled={
                  mutationsBlocked || terminalBusy || uncertain !== null
                }
                onChange={(answer) => {
                  const previous = coordinator.answers[question.number]!;
                  coordinator.changeAnswer(question.number, answer);
                  if (!monitoring.enabled) return;
                  monitoring.onAnswerInteraction?.({
                    attemptId: attempt.attemptId,
                    questionNumber: question.number,
                    type: isUnanswered(answer)
                      ? "cleared"
                      : isUnanswered(previous)
                        ? "selected"
                        : "changed",
                  });
                }}
                question={question}
              />
            </div>
            {(["draft", "failed", "conflict", "uncertain"] as const).includes(
              coordinator.statuses[question.number]?.phase as
                "draft" | "failed" | "conflict" | "uncertain",
            ) ? (
              <div className="mt-5 flex flex-wrap gap-2">
                {coordinator.statuses[question.number]?.phase ===
                "uncertain" ? (
                  <>
                    <Button
                      disabled={mutationsBlocked}
                      onClick={() =>
                        void coordinator.retryUncertain(question.number)
                      }
                      size="compact"
                      variant="secondary"
                    >
                      {t("Retry exact save")}
                    </Button>
                    <Button
                      disabled={mutationsBlocked}
                      onClick={() =>
                        void coordinator.checkAuthoritativeState(
                          question.number,
                        )
                      }
                      size="compact"
                      variant="ghost"
                    >
                      {t("Check server")}
                    </Button>
                  </>
                ) : (
                  <Button
                    disabled={mutationsBlocked}
                    onClick={() =>
                      void (coordinator.statuses[question.number]?.phase ===
                      "conflict"
                        ? coordinator.saveConflict(question.number)
                        : coordinator.retryFailed(question.number))
                    }
                    size="compact"
                    variant="secondary"
                  >
                    {t("Save this draft")}
                  </Button>
                )}
              </div>
            ) : null}
          </Surface>
        </div>
      )}

      {attempt.state === "ACTIVE" ? (
        <Surface className="flex flex-wrap items-center justify-between gap-4 p-5">
          <p className="max-w-xl text-sm leading-6 text-foreground-secondary">
            {t(
              "Submission sends the complete answer collection and never truncates it. Abandon is distinct, irreversible, and creates no result.",
            )}
          </p>
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={mutationsBlocked || uncertain !== null || terminalBusy}
              onClick={() => setConfirmation("submit")}
            >
              {t("Submit attempt")}
            </Button>
            <Button
              disabled={mutationsBlocked || uncertain !== null || terminalBusy}
              onClick={() => setConfirmation("abandon")}
              variant="danger"
            >
              {t("Abandon attempt")}
            </Button>
          </div>
        </Surface>
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
              "Quizopia will save pending drafts, then submit exactly one answer for every question. The server computes and persists the grade atomically.",
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
              "Abandoning is irreversible, consumes this Attempt entitlement, and creates no result. Closing the browser never performs this action.",
            )}
          </p>
        </PublicationConfirmation>
      ) : null}
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
          {t("Server-issued result")}
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
              {formatDateTime(locale, result.finalizedAt)}
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
          "Correct answers and explanations are available only through the separate, server-authorized review route after durable Publication closure.",
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
            "{message} No correct-answer request was made before opening this explicit review page.",
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
            "The server released these details for this submitted owner after durable closure.",
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
