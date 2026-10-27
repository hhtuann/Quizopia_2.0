"use client";

import { useI18n } from "../../../lib/i18n/provider";
import type {
  Answer,
  MatrixAnswer,
  MultipleAnswer,
  Question,
} from "../api/attempt-contract.generated";
import type {
  AnswerSavePhase,
  AnswerSaveStatus,
} from "../hooks/use-attempt-coordinator";

function MarkdownText({ children }: { readonly children: string }) {
  return <span className="whitespace-pre-wrap break-words">{children}</span>;
}

/** Localized phase label shared by the save status widget and the
 * question-navigation accessible names. */
export function savePhaseLabel(
  phase: AnswerSavePhase,
  t: ReturnType<typeof useI18n>["t"],
): string {
  const labels: Record<AnswerSavePhase, string> = {
    saved: t("Saved"),
    draft: t("Unsaved draft"),
    saving: t("Saving…"),
    failed: t("Save failed"),
    conflict: t("Revision conflict"),
    uncertain: t("Save outcome uncertain"),
  };
  return labels[phase];
}

export function AnswerField({
  answer,
  disabled,
  onChange,
  question,
}: {
  readonly answer: Answer;
  readonly disabled: boolean;
  readonly onChange: (answer: Answer) => void;
  readonly question: Question;
}) {
  const { t } = useI18n();
  if (question.type === "SINGLE_CHOICE" && answer.type === question.type) {
    return (
      <fieldset className="space-y-3" disabled={disabled}>
        <legend className="sr-only">{t("Choose one answer")}</legend>
        {question.options.map((option) => (
          <label
            className="flex min-h-11 cursor-pointer items-start gap-3 rounded-xl border border-border bg-surface p-3 transition-colors hover:border-primary/50 has-[:checked]:border-primary has-[:checked]:bg-primary/5"
            key={option.label}
          >
            <input
              checked={answer.optionLabel === option.label}
              className="mt-1 size-4 accent-primary"
              name={`question-${question.number}`}
              onChange={() =>
                onChange({
                  type: "SINGLE_CHOICE",
                  optionLabel: option.label,
                })
              }
              type="radio"
            />
            <span className="min-w-0 text-sm leading-6 text-foreground">
              <strong className="mr-2 font-mono">{option.label}</strong>
              <MarkdownText>{option.markdown}</MarkdownText>
            </span>
          </label>
        ))}
        <button
          className="min-h-11 rounded-lg px-3 text-sm font-semibold text-foreground-secondary underline decoration-border-strong underline-offset-4 focus-visible:ring-2 focus-visible:ring-focus"
          onClick={() => onChange({ type: "SINGLE_CHOICE", optionLabel: null })}
          type="button"
        >
          {t("Clear answer")}
        </button>
      </fieldset>
    );
  }

  if (question.type === "MULTIPLE_CHOICE" && answer.type === question.type) {
    const selected = new Set(answer.optionLabels);
    return (
      <fieldset className="space-y-3" disabled={disabled}>
        <legend className="text-sm text-foreground-muted">
          {t("Select every answer that applies.")}
        </legend>
        {question.options.map((option) => (
          <label
            className="flex min-h-11 cursor-pointer items-start gap-3 rounded-xl border border-border bg-surface p-3 transition-colors hover:border-primary/50 has-[:checked]:border-primary has-[:checked]:bg-primary/5"
            key={option.label}
          >
            <input
              checked={selected.has(option.label)}
              className="mt-1 size-4 accent-primary"
              onChange={(event) => {
                const next = new Set(answer.optionLabels);
                if (event.target.checked) next.add(option.label);
                else next.delete(option.label);
                onChange({
                  type: "MULTIPLE_CHOICE",
                  optionLabels: [
                    ...next,
                  ].sort() as MultipleAnswer["optionLabels"],
                });
              }}
              type="checkbox"
            />
            <span className="min-w-0 text-sm leading-6 text-foreground">
              <strong className="mr-2 font-mono">{option.label}</strong>
              <MarkdownText>{option.markdown}</MarkdownText>
            </span>
          </label>
        ))}
      </fieldset>
    );
  }

  if (question.type === "TRUE_FALSE_MATRIX" && answer.type === question.type) {
    return (
      <fieldset className="space-y-4" disabled={disabled}>
        <legend className="text-sm text-foreground-muted">
          {t("Judge every statement. Unanswered is distinct from false.")}
        </legend>
        {question.options.map((option) => (
          <fieldset
            className="rounded-xl border border-border bg-surface p-4"
            key={option.label}
          >
            <legend className="w-full text-sm leading-6 text-foreground">
              <strong className="mr-2 font-mono">{option.label}</strong>
              <MarkdownText>{option.markdown}</MarkdownText>
            </legend>
            <div className="mt-3 flex flex-wrap gap-2">
              {(
                [
                  [true, t("True")],
                  [false, t("False")],
                  [null, t("Unanswered")],
                ] as const
              ).map(([value, label]) => (
                <label
                  className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-lg border border-border px-3 text-sm has-[:checked]:border-primary has-[:checked]:bg-primary/5"
                  key={label}
                >
                  <input
                    checked={answer.judgments[option.label] === value}
                    className="accent-primary"
                    name={`question-${question.number}-${option.label}`}
                    onChange={() =>
                      onChange({
                        type: "TRUE_FALSE_MATRIX",
                        judgments: {
                          ...answer.judgments,
                          [option.label]: value,
                        } as MatrixAnswer["judgments"],
                      })
                    }
                    type="radio"
                  />
                  {label}
                </label>
              ))}
            </div>
          </fieldset>
        ))}
      </fieldset>
    );
  }

  if (question.type === "NUMERIC_FILL" && answer.type === question.type) {
    return (
      <div className="max-w-sm space-y-2">
        <label
          className="block text-sm font-semibold text-foreground-secondary"
          htmlFor={`numeric-${question.number}`}
        >
          {t("Four-character numeric answer")}
        </label>
        <input
          autoComplete="off"
          className="min-h-11 w-full rounded-xl border border-border bg-surface px-4 font-mono text-base focus-visible:border-focus focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus/30 disabled:bg-surface-muted"
          disabled={disabled}
          id={`numeric-${question.number}`}
          inputMode="decimal"
          maxLength={64}
          onChange={(event) =>
            onChange({ type: "NUMERIC_FILL", value: event.target.value })
          }
          placeholder="2.50"
          spellCheck={false}
          value={answer.value ?? ""}
        />
        <p className="text-xs leading-5 text-foreground-muted">
          {t(
            "Examples: 2.50, 1234, -2.5, -.25. Blank means unanswered. No exponent, comma, plus sign, padding, or rounding is applied.",
          )}
        </p>
      </div>
    );
  }

  return (
    <p className="text-sm text-danger" role="alert">
      {t("The answer type does not match this question. Refresh to continue.")}
    </p>
  );
}

export function SaveStatus({ status }: { readonly status: AnswerSaveStatus }) {
  const { t } = useI18n();
  const tone =
    status.phase === "saved"
      ? "text-success"
      : status.phase === "draft" || status.phase === "saving"
        ? "text-warning"
        : "text-danger";
  return (
    <div aria-live="polite" className="text-sm">
      <p className={`font-semibold ${tone}`}>
        {savePhaseLabel(status.phase, t)}
      </p>
      {status.message ? (
        <p className="mt-1 max-w-2xl text-xs leading-5 text-foreground-secondary">
          {status.message}
        </p>
      ) : null}
    </div>
  );
}

export function AnswerSummary({ answer }: { readonly answer: Answer }) {
  const { t } = useI18n();
  if (answer.type === "SINGLE_CHOICE")
    return <span>{answer.optionLabel ?? t("Unanswered")}</span>;
  if (answer.type === "MULTIPLE_CHOICE")
    return <span>{answer.optionLabels.join(", ") || t("Unanswered")}</span>;
  if (answer.type === "NUMERIC_FILL")
    return <span className="font-mono">{answer.value ?? t("Unanswered")}</span>;
  return (
    <span>
      {Object.entries(answer.judgments)
        .map(
          ([label, value]) =>
            `${label}: ${
              value === null ? t("Unanswered") : value ? t("True") : t("False")
            }`,
        )
        .join(" · ")}
    </span>
  );
}
