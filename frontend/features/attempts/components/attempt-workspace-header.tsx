"use client";

import { useRouter } from "next/navigation";
import type { ReactNode } from "react";
import { QuizopiaLogo } from "../../../components/brand/quizopia-logo";
import { APPLICATION_HEADER_GEOMETRY } from "../../../components/ui/application-header-geometry";
import { Button } from "../../../components/ui/button";
import { FocusedBackButton } from "../../../components/ui/focused-back-button";
import { formatNumber } from "../../../lib/i18n/core";
import { useI18n } from "../../../lib/i18n/provider";
import type { AnswerSavePhase } from "../hooks/use-attempt-coordinator";
import { savePhaseLabel } from "./answer-fields";

/** Worst persistence phase across all questions, mirroring SaveStatus tones:
 * unresolved outcomes outrank in-flight work, which outranks local drafts. */
export function aggregateSavePhase(
  statuses: Readonly<Record<number, { readonly phase: AnswerSavePhase }>>,
): AnswerSavePhase {
  const phases = Object.values(statuses).map((status) => status.phase);
  if (phases.includes("uncertain")) return "uncertain";
  if (phases.includes("conflict")) return "conflict";
  if (phases.includes("failed")) return "failed";
  if (phases.includes("saving")) return "saving";
  if (phases.includes("draft")) return "draft";
  return "saved";
}

function savePhaseTone(phase: AnswerSavePhase): string {
  if (phase === "saved") return "text-success";
  if (phase === "draft" || phase === "saving") return "text-warning";
  return "text-danger";
}

export interface AttemptWorkspaceHeaderProps {
  /** Authoritative publication title threaded from the student's own
   * authorized entry preview; the caller passes a short localized generic
   * fallback only when no authorized title is available in this session. */
  readonly title: string;
  /** Authenticated student's own account username (never another student). */
  readonly studentName: string | null;
  /** Leaving guard: confirm before abandoning unsaved local work. */
  readonly onBack: () => void;
  /** Formatted remaining time (e.g. 00:29:49); null hides the timer entirely
   * (unlimited or terminal attempts must not show an invented timer). */
  readonly remainingLabel: string | null;
  /** Aggregate autosave phase; null hides the status block. */
  readonly savePhase: AnswerSavePhase | null;
  readonly answeredCount: number | null;
  readonly totalCount: number | null;
  readonly realtime: ReactNode;
  /** Terminal actions render only while the Attempt is ACTIVE; the disabled
   * flags reuse the existing pending/uncertain/failed-save guards. */
  readonly showActions: boolean;
  readonly actionsDisabled: boolean;
  readonly onSubmit?: () => void;
  readonly onAbandon?: () => void;
}

/**
 * Sticky chrome for the focused student attempt workspace. Geometry is shared
 * with the focused authoring editor: same header height/padding, brand mark
 * and wordmark rules, icon-only back control and vertical separator. No
 * language control lives here (the account menu owns language switching);
 * the visible header carries only student-meaningful information — exam
 * title, the authenticated student, a compact informative countdown,
 * autosave status and the Submit/Abandon actions. Technical identifiers
 * (state badge, attempt UUID, ordinal, revision) stay out of the navbar.
 */
export function AttemptWorkspaceHeader({
  title,
  studentName,
  onBack,
  remainingLabel,
  savePhase,
  answeredCount,
  totalCount,
  realtime,
  showActions,
  actionsDisabled,
  onSubmit,
  onAbandon,
}: AttemptWorkspaceHeaderProps) {
  const { t, locale } = useI18n();
  return (
    <header className={APPLICATION_HEADER_GEOMETRY}>
      <button
        aria-label={t("Quizopia home")}
        className="flex min-h-11 shrink-0 items-center rounded-lg text-foreground hover:text-primary focus-visible:ring-2 focus-visible:ring-focus"
        onClick={onBack}
        type="button"
      >
        <QuizopiaLogo
          markClassName="size-9 rounded-lg"
          wordmarkClassName="hidden xl:flex"
        />
      </button>
      <FocusedBackButton ariaLabel={t("Back to app")} onClick={onBack} />
      <span aria-hidden="true" className="hidden h-7 w-px bg-border sm:block" />
      <div className="order-3 min-w-0 w-full sm:order-none sm:w-auto sm:min-w-[16rem] sm:flex-1 sm:max-w-xl">
        <h1 className="truncate font-heading text-lg font-bold leading-tight text-foreground">
          {title}
        </h1>
        {studentName === null ? null : (
          <p className="mt-1 truncate text-xs text-foreground-muted">
            {t("Candidate: {name}", { name: studentName })}
          </p>
        )}
      </div>
      <div className="order-4 ml-auto flex w-full flex-wrap items-center justify-end gap-x-3 gap-y-2 sm:order-none sm:w-auto">
        {remainingLabel === null ? null : (
          <div className="flex min-h-11 min-w-28 shrink-0 items-center justify-center rounded-xl border border-border bg-background px-3 py-2">
            <span
              aria-label={t("Remaining time")}
              className="font-mono text-base font-bold tabular-nums text-foreground"
              role="timer"
            >
              {remainingLabel}
            </span>
          </div>
        )}
        {savePhase === null ? null : (
          <div aria-live="polite" className="min-w-28 shrink-0 text-right">
            <p className={`text-xs font-bold ${savePhaseTone(savePhase)}`}>
              {savePhaseLabel(savePhase, t)}
            </p>
            {answeredCount === null || totalCount === null ? null : (
              <p className="mt-0.5 text-xs tabular-nums text-foreground-muted">
                {t("{answered} of {total} answered", {
                  answered: formatNumber(locale, answeredCount),
                  total: formatNumber(locale, totalCount),
                })}
              </p>
            )}
          </div>
        )}
        {realtime}
        {showActions ? (
          <div className="flex shrink-0 items-center gap-2">
            <Button
              className="px-3 sm:px-5"
              disabled={actionsDisabled}
              onClick={onSubmit}
            >
              {t("Submit attempt")}
            </Button>
            <Button
              className="px-3 sm:px-5"
              disabled={actionsDisabled}
              onClick={onAbandon}
              variant="danger"
            >
              {t("Abandon attempt")}
            </Button>
          </div>
        ) : null}
      </div>
    </header>
  );
}
