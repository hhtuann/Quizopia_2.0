"use client";

import { useEffect, useId, useRef } from "react";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { useI18n } from "../../../lib/i18n/provider";
import type { Answer, Question } from "../api/attempt-contract.generated";
import type { AnswerSaveStatus } from "../hooks/use-attempt-coordinator";
import { AnswerField, SaveStatus } from "./answer-fields";

/**
 * One question card in the continuous vertical attempt list. The answer
 * controls are the existing AnswerField implementation, unchanged; per-card
 * identity is the canonical question number from the immutable snapshot, so
 * scrolling and locale changes never remount answer state.
 */
export function AttemptQuestionCard({
  answer,
  actionsDisabled,
  answersDisabled,
  onChange,
  onRetryFailed,
  onRetryUncertain,
  onCheckAuthoritative,
  question,
  status,
}: {
  readonly answer: Answer;
  readonly actionsDisabled: boolean;
  readonly answersDisabled: boolean;
  readonly onChange: (answer: Answer) => void;
  readonly onRetryFailed: () => void;
  readonly onRetryUncertain: () => void;
  readonly onCheckAuthoritative: () => void;
  readonly question: Question;
  readonly status: AnswerSaveStatus;
}) {
  const { t } = useI18n();
  const headingId = useId();
  return (
    <Surface
      aria-labelledby={headingId}
      className="min-w-0 scroll-mt-36 p-5 sm:p-6"
      data-question-number={question.number}
      id={`attempt-question-${question.number}`}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-xs font-bold uppercase tracking-wide text-primary">
            {t("Question {number} · {type}", {
              number: question.number,
              type: question.type.replaceAll("_", " "),
            })}
          </p>
          <h2
            className="mt-3 whitespace-pre-wrap break-words text-lg font-bold leading-8"
            id={headingId}
          >
            {question.stemMarkdown}
          </h2>
        </div>
        <SaveStatus status={status} />
      </div>
      <div className="mt-6">
        <AnswerField
          answer={answer}
          disabled={answersDisabled}
          onChange={onChange}
          question={question}
        />
      </div>
      {(["draft", "failed", "conflict", "uncertain"] as const).includes(
        status.phase as "draft" | "failed" | "conflict" | "uncertain",
      ) ? (
        <div className="mt-5 flex flex-wrap gap-2">
          {status.phase === "uncertain" ? (
            <>
              <Button
                disabled={actionsDisabled}
                onClick={onRetryUncertain}
                size="compact"
                variant="secondary"
              >
                {t("Retry exact save")}
              </Button>
              <Button
                disabled={actionsDisabled}
                onClick={onCheckAuthoritative}
                size="compact"
                variant="ghost"
              >
                {t("Check server")}
              </Button>
            </>
          ) : (
            <Button
              disabled={actionsDisabled}
              onClick={onRetryFailed}
              size="compact"
              variant="secondary"
            >
              {t("Save this draft")}
            </Button>
          )}
        </div>
      ) : null}
    </Surface>
  );
}

/**
 * Continuous vertical list of every question in canonical snapshot order.
 * An IntersectionObserver keeps the navigator's current-question indicator in
 * sync with scroll position without firing monitoring events or shifting
 * layout; explicit navigator jumps remain the deliberate selection action.
 */
export function AttemptQuestionList({
  actionsDisabled,
  answers,
  answersDisabled,
  onAnswerChange,
  onCheckAuthoritative,
  onRetryFailed,
  onRetryUncertain,
  onVisibleQuestionChange,
  questions,
  statuses,
}: {
  readonly actionsDisabled: boolean;
  readonly answers: Record<number, Answer>;
  readonly answersDisabled: boolean;
  readonly onAnswerChange: (questionNumber: number, answer: Answer) => void;
  readonly onCheckAuthoritative: (questionNumber: number) => void;
  readonly onRetryFailed: (questionNumber: number) => void;
  readonly onRetryUncertain: (questionNumber: number) => void;
  readonly onVisibleQuestionChange: (questionNumber: number) => void;
  readonly questions: readonly Question[];
  readonly statuses: Record<number, AnswerSaveStatus>;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const root = containerRef.current;
    if (root === null || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(
      (entries) => {
        // Prefer the topmost card whose top edge has cleared the sticky
        // header; when the document end clamps the last card lower in the
        // viewport, that card is still the one the reader is at.
        const bandTop = 64;
        const intersecting = entries.filter((entry) => entry.isIntersecting);
        const clearedHeader = intersecting.filter(
          (entry) => entry.boundingClientRect.top >= bandTop,
        );
        const pool =
          clearedHeader.length > 0 ? clearedHeader : intersecting.slice(-1);
        const target = pool.sort(
          (left, right) =>
            left.boundingClientRect.top - right.boundingClientRect.top,
        )[0]?.target as HTMLElement | undefined;
        const questionNumber = target?.dataset.questionNumber;
        if (questionNumber !== undefined)
          onVisibleQuestionChange(Number(questionNumber));
      },
      // Observe the reading band below the sticky header.
      { rootMargin: "-88px 0px -35% 0px", threshold: 0 },
    );
    for (const card of root.querySelectorAll("[data-question-number]"))
      observer.observe(card);
    return () => observer.disconnect();
  }, [questions, onVisibleQuestionChange]);
  return (
    <div className="space-y-5" ref={containerRef}>
      {questions.map((question) => (
        <AttemptQuestionCard
          actionsDisabled={actionsDisabled}
          answer={answers[question.number]!}
          answersDisabled={answersDisabled}
          key={question.number}
          onChange={(answer) => onAnswerChange(question.number, answer)}
          onCheckAuthoritative={() => onCheckAuthoritative(question.number)}
          onRetryFailed={() => onRetryFailed(question.number)}
          onRetryUncertain={() => onRetryUncertain(question.number)}
          question={question}
          status={statuses[question.number] ?? { phase: "draft" }}
        />
      ))}
    </div>
  );
}
