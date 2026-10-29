"use client";

import { useId, useState } from "react";
import { useI18n } from "../../../lib/i18n/provider";
import type { Answer, Question } from "../api/attempt-contract.generated";
import type {
  AnswerSavePhase,
  AnswerSaveStatus,
} from "../hooks/use-attempt-coordinator";
import { isUnanswered } from "../model/attempt";
import { savePhaseLabel } from "./answer-fields";

/** Scroll honoring prefers-reduced-motion for navigator jumps. */
export function prefersReducedMotion(): boolean {
  return (
    typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}

export function scrollQuestionIntoView(questionNumber: number): void {
  document
    .getElementById(`attempt-question-${questionNumber}`)
    ?.scrollIntoView?.({
      behavior: prefersReducedMotion() ? "auto" : "smooth",
      block: "start",
    });
}

/**
 * Numbered-box presentation. Precedence: flagged (amber) > unresolved save
 * error (red honesty indicator) > in-flight save (blue) > answered (green) >
 * unanswered (neutral). The current question keeps aria-current plus a
 * restrained neutral ring that never overrides the state colors. No solid
 * purple fills and no checkmarks.
 */
function boxClasses(
  current: boolean,
  answered: boolean,
  flagged: boolean,
  phase: AnswerSavePhase,
): string {
  let state = "border-border bg-surface text-foreground";
  if (answered) state = "border-success/60 bg-success/10 text-foreground";
  if (phase === "saving") state = "border-info/50 bg-info/5 text-foreground";
  if (phase === "failed" || phase === "conflict" || phase === "uncertain")
    state = "border-danger/60 bg-danger/10 text-foreground";
  if (flagged) state = "border-warning bg-warning/15 text-foreground";
  const currentRing = current
    ? " ring-1 ring-foreground/40 font-extrabold"
    : "";
  return `${state}${currentRing}`;
}

/**
 * One numbered box per question, keyed by the canonical question number from
 * the immutable publication snapshot. The accessible name carries the
 * answered, flagged and exact save-phase state, so persistence is never
 * implied while a save is in flight, failed, conflicting or uncertain.
 */
export function AttemptQuestionNavigatorBoxes({
  answers,
  currentNumber,
  flags,
  onJump,
  questions,
  statuses,
}: {
  readonly answers: Record<number, Answer>;
  readonly currentNumber: number;
  /** Question numbers the student flagged for review (client-side state). */
  readonly flags: ReadonlySet<number>;
  readonly onJump: (questionNumber: number) => void;
  readonly questions: readonly Question[];
  readonly statuses: Record<number, AnswerSaveStatus>;
}) {
  const { t } = useI18n();
  return (
    <ol className="grid grid-cols-5 gap-2 sm:grid-cols-8 lg:grid-cols-4">
      {questions.map((question) => {
        const answer = answers[question.number];
        const answered = answer !== undefined && !isUnanswered(answer);
        const phase = statuses[question.number]?.phase ?? "saved";
        const flagged = flags.has(question.number);
        const current = currentNumber === question.number;
        return (
          <li key={question.number}>
            <button
              aria-current={current ? "true" : undefined}
              aria-label={t("Question {number}, {status}", {
                number: question.number,
                status: `${flagged ? `${t("Flagged")} · ` : ""}${
                  answered ? t("Answered") : t("Unanswered")
                } · ${savePhaseLabel(phase, t)}`,
              })}
              className={`flex min-h-11 w-full items-center justify-center rounded-lg border font-mono text-sm font-bold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus motion-reduce:transition-none ${boxClasses(current, answered, flagged, phase)}`}
              onClick={() => onJump(question.number)}
              type="button"
            >
              {question.number}
            </button>
          </li>
        );
      })}
    </ol>
  );
}

/**
 * Question navigator for the attempt workspace. Desktop (lg+) keeps a sticky
 * box grid beside the question list; narrower viewports collapse the same
 * boxes behind an aria-expanded toggle so the workspace stays readable at
 * 390px without horizontal overflow.
 */
export function AttemptQuestionNavigator({
  answers,
  currentNumber,
  flags,
  onJump,
  questions,
  statuses,
}: {
  readonly answers: Record<number, Answer>;
  readonly currentNumber: number;
  readonly flags: ReadonlySet<number>;
  readonly onJump: (questionNumber: number) => void;
  readonly questions: readonly Question[];
  readonly statuses: Record<number, AnswerSaveStatus>;
}) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const panelId = useId();
  return (
    <nav
      aria-label={t("Attempt questions")}
      className="min-w-0 lg:sticky lg:top-24 lg:self-start"
    >
      <div className="hidden lg:block rounded-xl border border-border bg-surface p-4 shadow-card">
        <p className="text-sm font-bold text-foreground">{t("Questions")}</p>
        <div className="mt-3">
          <AttemptQuestionNavigatorBoxes
            answers={answers}
            currentNumber={currentNumber}
            flags={flags}
            onJump={onJump}
            questions={questions}
            statuses={statuses}
          />
        </div>
      </div>
      <div className="lg:hidden">
        <button
          aria-controls={panelId}
          aria-expanded={open}
          className="flex min-h-11 w-full items-center justify-between gap-2 rounded-xl border border-border bg-surface px-4 text-sm font-semibold text-foreground transition-colors hover:border-primary/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus motion-reduce:transition-none"
          onClick={() => setOpen((value) => !value)}
          type="button"
        >
          <span className="flex items-center gap-2">
            <svg
              aria-hidden="true"
              className="size-4"
              fill="none"
              stroke="currentColor"
              strokeLinecap="round"
              strokeWidth="2"
              viewBox="0 0 24 24"
            >
              <path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01" />
            </svg>
            {t("Questions")}
          </span>
          <svg
            aria-hidden="true"
            className={`size-4 transition-transform motion-reduce:transition-none ${open ? "rotate-180" : ""}`}
            fill="none"
            stroke="currentColor"
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth="2"
            viewBox="0 0 24 24"
          >
            <path d="m6 9 6 6 6-6" />
          </svg>
        </button>
        {open ? (
          <div
            className="mt-2 rounded-xl border border-border bg-surface p-3"
            id={panelId}
          >
            <AttemptQuestionNavigatorBoxes
              answers={answers}
              currentNumber={currentNumber}
              flags={flags}
              onJump={onJump}
              questions={questions}
              statuses={statuses}
            />
          </div>
        ) : null}
      </div>
    </nav>
  );
}
