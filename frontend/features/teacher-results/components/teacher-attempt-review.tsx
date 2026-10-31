"use client";
import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { Alert } from "../../../components/ui/alert";
import { useI18n } from "../../../lib/i18n/provider";
import { formatInstant, formatDecimal } from "../../../lib/i18n/core";
import { AnswerSummary } from "../../attempts/components/answer-fields";
import { observationClient } from "../../attempts/audit/observation-client";
import type {
  AuditTimeline,
  TeacherAttemptReview,
} from "../api/teacher-assessment-contract.generated";

/** Narrow submitted-review surface for T2; no Unified Dashboard or live-answer viewer. */
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
  const { t, locale } = useI18n();
  const [view, setView] = useState<TeacherAttemptReview | null>(null),
    [timeline, setTimeline] = useState<AuditTimeline | null>(null),
    [failed, setFailed] = useState(false),
    [busy, setBusy] = useState(false);
  const lifetime = useRef<AbortController | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    return () => controller.abort();
  }, []);
  async function read() {
    const controller = lifetime.current;
    if (controller === null || controller.signal.aborted) return;
    setBusy(true);
    const result = await client.review(controller.signal);
    if (controller.signal.aborted) return;
    setView(result);
    setTimeline(null);
    setFailed(result === null);
    setBusy(false);
  }
  async function page(after: string) {
    const controller = lifetime.current;
    if (controller === null || controller.signal.aborted) return;
    setBusy(true);
    const result = await client.timeline(after, controller.signal);
    if (controller.signal.aborted) return;
    if (result === null) {
      setView(null);
      setTimeline(null);
      setFailed(true);
    } else setTimeline(result);
    setBusy(false);
  }
  return (
    <div className="space-y-5">
      <Link
        className="text-sm font-semibold text-primary underline"
        href={`/app/assessments/${publicationId}/results`}
      >
        {t("Back to Publication details")}
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
      {failed ? (
        <Alert title={t("Answer review unavailable")} variant="warning">
          {t(
            "No protected content is available. Check your current access or try again.",
          )}
        </Alert>
      ) : null}
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
              "History may be incomplete. Missing observations do not prove that an action did not happen.",
            )}
          </p>
          <p className="text-sm">
            {timeline.coverage.status} · {timeline.coverage.reasons.join(" · ")}
          </p>
          <ol className="space-y-3">
            {timeline.items.map((item) => (
              <li className="border-l-2 border-primary pl-3" key={item.eventId}>
                {item.questionNumber === null ? null : (
                  <p>
                    {t("Question {number}", { number: item.questionNumber })}
                  </p>
                )}
                <p>
                  {item.source} · {item.type}
                  {item.terminalReason ? ` · ${item.terminalReason}` : ""}
                </p>
                <p className="text-sm">
                  {t("Received")}: {formatInstant(locale, item.receivedAt)}
                  {item.occurredAt
                    ? ` · ${t("Occurred")}: ${formatInstant(locale, item.occurredAt)}`
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
                  <p className="text-sm">{item.transitionDetailStatus}</p>
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
