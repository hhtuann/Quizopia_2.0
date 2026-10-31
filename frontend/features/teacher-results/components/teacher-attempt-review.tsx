"use client";

import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { Alert } from "../../../components/ui/alert";
import { StateBadge } from "../../../components/ui/state-badge";
import { useI18n } from "../../../lib/i18n/provider";
import { formatInstant, formatDecimal } from "../../../lib/i18n/core";
import { AnswerSummary } from "../../attempts/components/answer-fields";
import { observationClient } from "../../attempts/audit/observation-client";
import type {
  AuditItem,
  AuditTimeline,
  TeacherAttemptReview,
} from "../api/teacher-assessment-contract.generated";

const auditSourceLabels: Record<AuditItem["source"], string> = {
  SERVER_AUTHORITY: "Server record",
  CLIENT_OBSERVATION: "Browser report",
};

const auditSourceVariants: Record<AuditItem["source"], "info" | "neutral"> = {
  SERVER_AUTHORITY: "info",
  CLIENT_OBSERVATION: "neutral",
};

const auditTypeLabels: Record<AuditItem["type"], string> = {
  ATTEMPT_STARTED: "Attempt started",
  ANSWER_PERSISTED: "Answer saved",
  ATTEMPT_SUBMITTED: "Attempt submitted",
  ATTEMPT_ABANDONED: "Attempt abandoned",
  CLOSURE_NOTICE_CONFIRMED: "Closure notice confirmed",
  RESULT_GRADED: "Result graded",
  QUESTION_VIEWED: "Question opened",
  QUESTION_LEFT: "Question left",
  ANSWER_SELECTED: "Answer selected",
  ANSWER_CHANGED: "Answer changed",
  ANSWER_CLEARED: "Answer cleared",
};

const coverageReasonLabels: Record<
  AuditTimeline["coverage"]["reasons"][number],
  string
> = {
  COLLECTION_OFF: "Optional collection is off",
  LEGACY: "Earlier attempts have no history",
  UNSUPPORTED_TYPE: "Some question types have no detail",
  CLIENT_GAP: "Some browser reports are missing",
  EXPIRED: "Some history has expired",
};

const transitionStatusLabels: Record<
  AuditItem["transitionDetailStatus"],
  string
> = {
  AVAILABLE: "Detail available",
  UNSUPPORTED_TYPE: "No detail for this question type",
  MISSING: "Detail missing",
  EXPIRED: "Detail expired",
  NOT_APPLICABLE: "No answer detail applies",
};

type ReviewFailure =
  | { readonly kind: "policy" }
  | { readonly kind: "denied" }
  | { readonly kind: "state" }
  | { readonly kind: "unavailable" };

function classifyFailure(status: number, code: string | null): ReviewFailure {
  if (status === 503 && code === "SENSITIVE_POLICY_PENDING")
    return { kind: "policy" };
  if (status === 403 || status === 401) return { kind: "denied" };
  if (status === 409 || status === 404) return { kind: "state" };
  return { kind: "unavailable" };
}

/** Narrow submitted-review surface; no live-answer viewer and no camera embedding. */
export function TeacherAttemptReviewPage({
  publicationId,
  attemptId,
}: {
  readonly publicationId: string;
  readonly attemptId: string;
}) {
  const { authenticatedRequests, getSessionGeneration, user } = useAuth();
  const generation = getSessionGeneration();
  const { t } = useI18n();
  const client = useMemo(
    () =>
      authenticatedRequests === null || generation === null
        ? null
        : observationClient(
            authenticatedRequests,
            getSessionGeneration,
            publicationId,
            attemptId,
          ),
    [
      authenticatedRequests,
      getSessionGeneration,
      generation,
      publicationId,
      attemptId,
    ],
  );
  return (
    <AuthenticatedBoundary>
      <ApplicationShell currentSection="assessments">
        {!user?.roles.includes("TEACHER") || client === null ? (
          <Alert title={t("Teacher access required")} variant="warning">
            {t("You need the Teacher role and ownership of this assessment.")}
          </Alert>
        ) : (
          <ReviewContent
            client={client}
            key={`${user.id}:${generation}:${publicationId}:${attemptId}`}
            publicationId={publicationId}
          />
        )}
      </ApplicationShell>
    </AuthenticatedBoundary>
  );
}

export function ReviewContent({
  client,
  publicationId,
}: {
  readonly client: ReturnType<typeof observationClient>;
  readonly publicationId: string;
}) {
  const { t, localize, locale } = useI18n();
  const [view, setView] = useState<TeacherAttemptReview | null>(null),
    [timeline, setTimeline] = useState<AuditTimeline | null>(null),
    [failed, setFailed] = useState<ReviewFailure | null>(null),
    [timelineFailed, setTimelineFailed] = useState<ReviewFailure | null>(null),
    [busy, setBusy] = useState(false);
  const lifetime = useRef<AbortController | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    return () => controller.abort();
  }, []);

  function failureAlert(kind: ReviewFailure, title: string) {
    if (kind.kind === "policy")
      return (
        <Alert title={title} variant="warning">
          {t(
            "Sensitive review is not available in this environment. Nothing is shown.",
          )}
        </Alert>
      );
    if (kind.kind === "denied")
      return (
        <Alert title={title} variant="warning">
          {t(
            "This attempt is not available to your account. Check that you still own this assessment.",
          )}
        </Alert>
      );
    if (kind.kind === "state")
      return (
        <Alert title={title} variant="warning">
          {t(
            "Only submitted and graded attempts can be reviewed here. Retakes in progress and abandoned attempts stay closed.",
          )}
        </Alert>
      );
    return (
      <Alert title={title} variant="warning">
        {t(
          "The review could not be read right now. No protected content is shown; try again.",
        )}
      </Alert>
    );
  }

  async function read() {
    const controller = lifetime.current;
    if (controller === null || controller.signal.aborted) return;
    setBusy(true);
    const result = await client.review(controller.signal);
    if (controller.signal.aborted) return;
    if (result.ok) {
      setView(result.value);
      setFailed(null);
    } else {
      setView(null);
      setTimeline(null);
      setFailed(classifyFailure(result.status, result.code));
    }
    setBusy(false);
  }
  async function page(after: string) {
    const controller = lifetime.current;
    if (controller === null || controller.signal.aborted) return;
    setBusy(true);
    const result = await client.timeline(after, controller.signal);
    if (controller.signal.aborted) return;
    if (result.ok) {
      setTimeline(result.value);
      setTimelineFailed(null);
    } else {
      setTimeline(null);
      setTimelineFailed(classifyFailure(result.status, result.code));
    }
    setBusy(false);
  }
  return (
    <div className="space-y-5">
      <Link
        className="text-sm font-semibold text-primary underline"
        href={`/app/assessments/${publicationId}/results`}
      >
        {t("Back to student results")}
      </Link>
      <h1 className="font-heading text-2xl font-bold">
        {t("Submitted Attempt review")}
      </h1>
      <p>
        {t(
          "Fictional private demo. Each sensitive read is recorded. Only submitted and graded work is available here; Student review still waits for closure.",
        )}
      </p>
      <Button disabled={busy} onClick={() => void read()}>
        {t("Read submitted work")}
      </Button>
      {failed !== null
        ? failureAlert(failed, t("Answer review unavailable"))
        : null}
      {view === null ? null : (
        <>
          <p>
            {formatInstant(locale, view.startedAt)} —{" "}
            {formatInstant(locale, view.finalizedAt)} · {view.state}
          </p>
          <p>
            {t("Score")}: {view.result.percentageDisplay}%
          </p>
          {view.questions.map((item) => (
            <Surface className="space-y-3 p-5" key={item.question.number}>
              <h2 className="font-heading text-lg font-semibold">
                {t("Question {number} · {earned}/{possible}", {
                  number: item.question.number,
                  earned: formatDecimal(locale, item.earnedWeight),
                  possible: formatDecimal(locale, item.possibleWeight),
                })}
              </h2>
              <p className="whitespace-pre-wrap">
                {item.question.stemMarkdown}
              </p>
              {item.question.options.map((option) => (
                <p key={option.label}>
                  {option.label}. {option.markdown}
                </p>
              ))}
              <dl>
                <dt className="font-semibold">{t("Submitted answer")}</dt>
                <dd>
                  <AnswerSummary answer={item.answer} />
                </dd>
                <dt className="mt-3 font-semibold">{t("Correct answer")}</dt>
                <dd>
                  <AnswerSummary answer={item.correctAnswer} />
                </dd>
              </dl>
              {item.explanationMarkdown ? (
                <p className="whitespace-pre-wrap">
                  {item.explanationMarkdown}
                </p>
              ) : null}
            </Surface>
          ))}
          <Button
            variant="secondary"
            disabled={busy}
            onClick={() => void page("0")}
          >
            {t("Read interaction timeline")}
          </Button>
        </>
      )}
      {timelineFailed !== null
        ? failureAlert(timelineFailed, t("Interaction timeline unavailable"))
        : null}
      {timeline === null ? null : (
        <Surface
          className="space-y-3 p-5"
          aria-label={t("Interaction timeline")}
        >
          <h2 className="font-heading text-lg font-semibold">
            {t("Interaction timeline")}
          </h2>
          <p>
            {t(
              "Server records are trusted facts. Browser reports are optional claims and never proof of misconduct; history may be incomplete.",
            )}
          </p>
          <p className="text-sm">
            {timeline.coverage.status} ·{" "}
            {timeline.coverage.reasons
              .map((reason) => localize(coverageReasonLabels[reason]))
              .join(" · ")}
          </p>
          <ol className="space-y-3">
            {timeline.items.map((item) => (
              <li className="border-l-2 border-primary pl-3" key={item.eventId}>
                {item.questionNumber === null ? null : (
                  <p>
                    {t("Question {number}", { number: item.questionNumber })}
                  </p>
                )}
                <p className="flex flex-wrap items-center gap-2">
                  <StateBadge variant={auditSourceVariants[item.source]}>
                    {localize(auditSourceLabels[item.source])}
                  </StateBadge>
                  <span className="font-semibold">
                    {localize(auditTypeLabels[item.type])}
                  </span>
                  {item.terminalReason ? (
                    <span className="text-sm text-foreground-secondary">
                      {item.terminalReason}
                    </span>
                  ) : null}
                </p>
                <p className="text-sm">
                  {t("Server received")}:{" "}
                  {formatInstant(locale, item.receivedAt)}
                  {item.occurredAt
                    ? ` · ${t("Recorded at")}: ${formatInstant(locale, item.occurredAt)}`
                    : ""}
                  {item.observedAt
                    ? ` · ${t("Browser reported")}: ${formatInstant(locale, item.observedAt)}`
                    : ""}
                </p>
                {item.transition ? (
                  <p>
                    {item.transition.fromOptionLabels.join(", ") || "∅"} →{" "}
                    {item.transition.toOptionLabels.join(", ") || "∅"}
                  </p>
                ) : (
                  <p className="text-sm">
                    {localize(
                      transitionStatusLabels[item.transitionDetailStatus],
                    )}
                  </p>
                )}
              </li>
            ))}
          </ol>
          {timeline.nextAfter === null ? null : (
            <Button
              variant="secondary"
              disabled={busy}
              onClick={() => void page(timeline.nextAfter!)}
            >
              {t("Next timeline page")}
            </Button>
          )}
        </Surface>
      )}
    </div>
  );
}
