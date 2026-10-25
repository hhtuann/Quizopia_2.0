"use client";
import { useEffect, useState, type FormEvent } from "react";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { DateTimeField } from "../../../components/ui/date-time-field";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
import { useUnsavedNavigationGuard } from "../../attempts/hooks/use-unsaved-navigation-guard";
import type {
  OwnerView,
  Schedule,
} from "../api/publication-contract.generated";
import { formatUtc, instantMicroseconds } from "../utils/utc-time";
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
  useUnsavedNavigationGuard(
    dirty,
    "This schedule extension has unsaved values. Leave this page and discard them?",
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
      <h2 className="font-heading text-xl font-bold">Extend OPEN schedule</h2>
      <p className="text-sm leading-6 text-foreground-muted">
        Start remains fixed. End and duration can only stay the same or
        increase. Extensions apply to future starts and do not recalculate
        existing Attempt deadlines. Pending closure may block changes.
      </p>
      <form className="space-y-4" noValidate onSubmit={submit}>
        <fieldset className="min-w-0 space-y-4" disabled={disabled}>
          <legend className="sr-only">Schedule extension</legend>
          <DateTimeField
            error={error?.startsWith("Enter a valid local") ? error : undefined}
            helperText={`Current authoritative end: ${formatUtc(currentEnd)}. Leave unchanged to keep it exactly, including seconds and microseconds.`}
            label="Extended participation end"
            onChange={(instant) => setEditedEnd(instant)}
            required
            value={currentEnd}
          />
          {publication.configuration.durationMinutes > 0 ? (
            <TextField
              label="Extended Attempt duration (minutes)"
              max={240}
              min={publication.configuration.durationMinutes}
              onChange={(event) => setDuration(event.target.value)}
              required
              step={1}
              type="number"
              value={duration}
            />
          ) : (
            <p>Attempt duration remains unlimited.</p>
          )}
        </fieldset>
        {error && !error.startsWith("Enter a valid local") ? (
          <Alert title="Check the schedule extension" variant="danger">
            {error}
          </Alert>
        ) : null}
        <Button
          aria-label={isPending ? "Saving schedule extension" : undefined}
          disabled={disabled}
          isLoading={isPending}
          loadingLabel="Saving schedule extension"
          type="submit"
        >
          Review schedule extension
        </Button>
      </form>
      {review ? (
        <PublicationConfirmation
          confirmLabel="Save schedule extension"
          onCancel={() => setReview(null)}
          onConfirm={() => {
            const input = review;
            setReview(null);
            onSubmit(input);
          }}
          title="Review OPEN schedule extension"
        >
          <PublicationSummary
            configuration={{
              ...publication.configuration,
              endAt: review.endAt,
              durationMinutes: review.durationMinutes,
            }}
          />
          <p>Existing Attempt deadlines remain unchanged.</p>
        </PublicationConfirmation>
      ) : null}
    </Surface>
  );
}
