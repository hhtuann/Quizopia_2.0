"use client";
import { useState, type FormEvent } from "react";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
import type {
  OwnerView,
  Schedule,
} from "../api/publication-contract.generated";
import {
  instantMicroseconds,
  instantToUtcField,
  utcFieldToInstant,
} from "../utils/utc-time";
import { PublicationConfirmation } from "./publication-confirmation";
import { PublicationSummary } from "./publication-summary";

export function ScheduleExtension({
  publication,
  disabled,
  isPending,
  onSubmit,
}: {
  readonly publication: OwnerView;
  readonly disabled: boolean;
  readonly isPending: boolean;
  readonly onSubmit: (input: Schedule) => void;
}) {
  const [endAt, setEndAt] = useState(() =>
    instantToUtcField(publication.configuration.endAt),
  );
  const [duration, setDuration] = useState(
    String(publication.configuration.durationMinutes),
  );
  const [error, setError] = useState<string | null>(null);
  const [review, setReview] = useState<Schedule | null>(null);
  function submit(event: FormEvent) {
    event.preventDefault();
    if (disabled || review !== null) return;
    const instant = utcFieldToInstant(endAt);
    const nextDuration = duration === "" ? -1 : Number(duration);
    if (
      instant === null ||
      instantMicroseconds(instant)! <
        instantMicroseconds(publication.configuration.endAt)!
    ) {
      setError("Participation end must be valid UTC and cannot decrease.");
      return;
    }
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
          <TextField
            helperText="UTC text: YYYY-MM-DDTHH:mm:ss, optionally .ffffff (up to six digits)."
            label="Extended participation end (UTC)"
            onChange={(event) => setEndAt(event.target.value)}
            required
            placeholder="2026-10-16T09:30:00.123456"
            type="text"
            value={endAt}
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
        {error ? (
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
