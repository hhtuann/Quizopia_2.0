"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { QuizopiaLogo } from "../../../components/brand/quizopia-logo";
import { APPLICATION_HEADER_GEOMETRY } from "../../../components/ui/application-header-geometry";
import { Button } from "../../../components/ui/button";
import { LanguageMenu } from "../../../components/ui/language-menu";
import { StateBadge } from "../../../components/ui/state-badge";
import { formatNumber } from "../../../lib/i18n/core";
import { useI18n } from "../../../lib/i18n/provider";
import type { AttemptView } from "../api/attempt-contract.generated";
import type { AnswerSavePhase } from "../hooks/use-attempt-coordinator";
import { savePhaseLabel } from "./answer-fields";

/** Semantic StateBadge variants for server-returned Attempt states. */
export const attemptStateVariants: Record<
  "ACTIVE" | "SUBMITTED" | "ABANDONED",
  "info" | "success" | "neutral"
> = {
  ACTIVE: "info",
  SUBMITTED: "success",
  ABANDONED: "neutral",
};

const backLinkClasses =
  "quiz-button-neutral-outline inline-flex min-h-11 shrink-0 items-center justify-center gap-2 rounded-full border px-3 text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none";

function savePhaseTone(phase: AnswerSavePhase): string {
  if (phase === "saved") return "text-success";
  if (phase === "draft" || phase === "saving") return "text-warning";
  return "text-danger";
}

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

export interface AttemptWorkspaceHeaderProps {
  /** Attempt/publication title; callers pass the localized fallback when the
   * attempt payload carries no authoritative title. */
  readonly title: string;
  readonly state: AttemptView["state"] | null;
  readonly attemptId: string | null;
  readonly ordinal: string | null;
  /** Formatted informative countdown; null hides the timer entirely (unlimited
   * or terminal attempts must not show an invented timer). */
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
 * Sticky chrome for the focused student attempt workspace. Mirrors the focused
 * authoring editor geometry (brand mark, explicit back-to-app path, language
 * menu) and carries the honest attempt status: informative countdown only when
 * a deadline exists, aggregate autosave phase labels, answered progress and
 * the Submit/Abandon actions with unchanged confirmation flows.
 */
export function AttemptWorkspaceHeader({
  title,
  state,
  attemptId,
  ordinal,
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
      <Link
        aria-label={t("Quizopia home")}
        className="flex min-h-11 shrink-0 items-center rounded-lg text-foreground hover:text-primary focus-visible:ring-2 focus-visible:ring-focus"
        href="/app"
      >
        <QuizopiaLogo
          markClassName="size-9 rounded-lg"
          wordmarkClassName="hidden md:flex"
        />
      </Link>
      <Link
        aria-label={t("Back to app")}
        className={backLinkClasses}
        href="/app"
      >
        <svg
          aria-hidden="true"
          className="size-5"
          fill="none"
          focusable="false"
          stroke="currentColor"
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth="2"
          viewBox="0 0 24 24"
        >
          <path d="m15 18-6-6 6-6" />
        </svg>
        <span className="hidden sm:inline">{t("Back to app")}</span>
      </Link>
      <LanguageMenu />
      <span aria-hidden="true" className="hidden h-7 w-px bg-border sm:block" />
      <div className="order-3 min-w-0 w-full sm:order-none sm:w-auto sm:min-w-[12rem] sm:flex-1 sm:max-w-xl">
        <h1 className="truncate font-heading text-lg font-bold leading-tight text-foreground">
          {title}
        </h1>
        <div className="mt-1 flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-foreground-muted">
          {state === null ? null : (
            <StateBadge variant={attemptStateVariants[state]}>
              {state}
            </StateBadge>
          )}
          {ordinal === null ? null : (
            <span>{t("Attempt {ordinal}", { ordinal })}</span>
          )}
          {attemptId === null ? null : (
            <span className="truncate font-mono">
              {t("Attempt {attemptId}", { attemptId })}
            </span>
          )}
        </div>
      </div>
      <div className="order-4 ml-auto flex w-full flex-wrap items-center justify-end gap-x-3 gap-y-2 sm:order-none sm:w-auto">
        {remainingLabel === null ? null : (
          <div className="flex min-w-28 shrink-0 flex-col items-end rounded-xl border border-border bg-background px-3 py-1.5 text-right">
            <span className="text-[10px] font-semibold uppercase tracking-wide text-foreground-muted">
              {t("Informative countdown")}
            </span>
            <span className="font-mono text-base font-bold tabular-nums text-foreground">
              <span role="timer">{remainingLabel}</span>
            </span>
            <span className="text-[10px] leading-4 text-foreground-muted">
              {t("Server time and refreshed status are authoritative.")}
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
              disabled={actionsDisabled}
              onClick={onSubmit}
              size="compact"
            >
              {t("Submit attempt")}
            </Button>
            <Button
              disabled={actionsDisabled}
              onClick={onAbandon}
              size="compact"
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
