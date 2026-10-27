"use client";
import { useEffect, useState, type FormEvent } from "react";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { DateTimeField } from "../../../components/ui/date-time-field";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
import { formatInstant } from "../../../lib/i18n/core";
import { useI18n } from "../../../lib/i18n/provider";
import { useUnsavedNavigationGuard } from "../../attempts/hooks/use-unsaved-navigation-guard";
import type {
  OwnerView,
  Schedule,
} from "../api/publication-contract.generated";
import { instantMicroseconds } from "../utils/utc-time";
import { PublicationConfirmation } from "./publication-confirmation";
import { PublicationSummary } from "./publication-summary";

export function ScheduleExtension({
  publication,
  disabled,
  isPending,
  onDirtyChange,
  onSubmit,
}: {
  readonly publication: OwnerView;
  readonly disabled: boolean;
  readonly isPending: boolean;
  readonly onSubmit: (input: Schedule) => void;
  /**
   * Notifies the parent whenever the form has unsaved values. The parent owns
   * the Hide toggle and must confirm before discarding them. Unsaved-work
   * protection itself is registered here and stays active regardless of
   * disabled state: a revision-conflicted form still holds unsaved values.
   */
  readonly onDirtyChange?: (dirty: boolean) => void;
}) {
  const { t, localize, locale } = useI18n();
  // The authoritative end instant, kept verbatim: an untouched field must
  // resubmit the exact server value, preserving seconds and microseconds.
  const currentEnd = publication.configuration.endAt;
  // Edited instant (millisecond ISO) once the user changes either part.
  // null means "untouched" — submit the authoritative value unchanged.
  const [editedEnd, setEditedEnd] = useState<string | null>(null);
  const [duration, setDuration] = useState(
    String(publication.configuration.durationMinutes),
  );
  const [error, setError] = useState<string | null>(null);
  const [review, setReview] = useState<Schedule | null>(null);
  const dirty =
    editedEnd !== null ||
    duration !== String(publication.configuration.durationMinutes);
  // Localized guard presentation recomputes per render; the stored value
  // stays an English catalog key.
  useUnsavedNavigationGuard(
    dirty,
    localize(
      "This schedule extension has unsaved values. Leave this page and discard them?",
    ),
  );
  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);
  function submit(event: FormEvent) {
    event.preventDefault();
    if (disabled || review !== null) return;
    const instant = editedEnd === null ? currentEnd : editedEnd;
    if (editedEnd === "") {
      setError(
        "Enter a valid local participation end date and time. Nonexistent local times (DST gaps) are not accepted.",
      );
      return;
    }
    if (
      instantMicroseconds(instant) === null ||
      instantMicroseconds(instant)! < instantMicroseconds(currentEnd)!
    ) {
      setError(
        "Participation end cannot decrease and must resolve to a valid instant.",
      );
      return;
    }
    const nextDuration = duration === "" ? -1 : Number(duration);
    if (
      !Number.isInteger(nextDuration) ||
      (publication.configuration.durationMinutes === 0
        ? nextDuration !== 0
        : nextDuration < publication.configuration.durationMinutes ||
          nextDuration > 240)
    ) {
      setError(
        "Duration cannot decrease, exceed 240, or switch bounded/unlimited mode.",
      );
      return;
    }
    setError(null);
    setReview({
      expectedRevision: publication.revision,
      endAt: instant,
      durationMinutes: nextDuration,
    });
  }
  return (
    <Surface className="space-y-4 p-5 sm:p-6">
      <h2 className="font-heading text-xl font-bold">
        {t("Extend OPEN schedule")}
      </h2>
      <p className="text-sm leading-6 text-foreground-muted">
        {t(
          "Start stays fixed; end and duration can only increase. Existing attempt deadlines are not recalculated. A pending closure request may block changes.",
        )}
      </p>
      <form className="space-y-4" noValidate onSubmit={submit}>
        <fieldset className="min-w-0 space-y-4" disabled={disabled}>
          <legend className="sr-only">{t("Schedule extension")}</legend>
          <DateTimeField
            error={
              error?.startsWith("Enter a valid local")
                ? localize(error)
                : undefined
            }
            helperText={t(
              "Current end: {end}. Leave unchanged to keep it exactly.",
              {
                end: formatInstant(locale, currentEnd, { withTimeZone: true }),
              },
            )}
            label={t("Extended participation end")}
            onChange={(instant) => setEditedEnd(instant)}
            required
            value={currentEnd}
          />
          {publication.configuration.durationMinutes > 0 ? (
            <TextField
              label={t("Extended Attempt duration (minutes)")}
              max={240}
              min={publication.configuration.durationMinutes}
              onChange={(event) => setDuration(event.target.value)}
              required
              step={1}
              type="number"
              value={duration}
            />
          ) : (
            <p>{t("Attempt duration remains unlimited.")}</p>
          )}
        </fieldset>
        {error && !error.startsWith("Enter a valid local") ? (
          <Alert title={t("Check the schedule extension")} variant="danger">
            {localize(error)}
          </Alert>
        ) : null}
        <Button
          aria-label={isPending ? t("Saving schedule extension") : undefined}
          disabled={disabled}
          isLoading={isPending}
          loadingLabel={t("Saving schedule extension")}
          type="submit"
        >
          {t("Review schedule extension")}
        </Button>
      </form>
      {review ? (
        <PublicationConfirmation
          confirmLabel={t("Save schedule extension")}
          onCancel={() => setReview(null)}
          onConfirm={() => {
            const input = review;
            setReview(null);
            onSubmit(input);
          }}
          title={t("Review OPEN schedule extension")}
        >
          <PublicationSummary
            configuration={{
              ...publication.configuration,
              endAt: review.endAt,
              durationMinutes: review.durationMinutes,
            }}
          />
          <p>{t("Existing Attempt deadlines remain unchanged.")}</p>
        </PublicationConfirmation>
      ) : null}
    </Surface>
  );
}
