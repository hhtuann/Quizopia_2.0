"use client";

import { useId, useState } from "react";

const CONTROL_CLASSES =
  "h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-foreground " +
  "placeholder:text-foreground-muted focus:border-focus focus:outline-none focus:ring-2 " +
  "focus:ring-focus/20 disabled:cursor-not-allowed disabled:bg-surface-muted disabled:text-foreground-disabled";

/**
 * Converts a UTC instant string to separate local date and time component values.
 */
export function instantToLocalParts(
  instant: string,
): { date: string; time: string } | null {
  if (!instant) return null;
  const d = new Date(instant);
  if (isNaN(d.getTime())) return null;
  const pad = (n: number, w = 2) => String(n).padStart(w, "0");
  return {
    date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
    time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
  };
}

/**
 * Converts local date + time strings to a UTC instant using the browser timezone.
 * Performs a round-trip validation: if the UTC instant converts back to a different
 * local wall-clock time, the input represents a nonexistent local time (DST
 * spring-forward gap) and is rejected rather than silently normalized.
 *
 * Ambiguous DST fall-back times (a local time that occurs twice) are accepted;
 * JavaScript's Date constructor resolves them to the first occurrence (pre-transition),
 * which is the standard behavior for native date/time inputs.
 */
export function localPartsToInstant(date: string, time: string): string | null {
  if (!date || !time) return null;
  const d = new Date(`${date}T${time}`);
  if (isNaN(d.getTime())) return null;
  const utc = d.toISOString();
  // Round-trip: if the instant maps back to a different wall-clock, the local
  // time is nonexistent (e.g., 02:30 during spring-forward) — reject it.
  const back = instantToLocalParts(utc);
  if (!back || back.date !== date || back.time !== time) return null;
  return utc;
}

/**
 * Returns a timezone label with the UTC offset calculated for the exact
 * selected local date and time when both are valid. DST-transition aware: on
 * spring-forward/fall-back days the offset follows the selected time, not the
 * date as a whole.
 *
 * Fallbacks for incomplete input, chosen so a misleading selected-instant
 * offset is never displayed:
 * - date only (no time yet): offset at local noon of that date;
 * - both parts present but the local time is nonexistent (DST gap, rejected by
 *   localPartsToInstant): local noon of that date rather than the normalized
 *   interpretation;
 * - no date: current instant.
 *
 * Ambiguous fall-back times (a local time occurring twice) display the offset
 * of the first occurrence, matching the documented first-occurrence policy of
 * localPartsToInstant.
 */
export function timezoneLabel(
  selectedDate?: string,
  selectedTime?: string,
): string {
  try {
    let instant: Date;
    if (selectedDate && selectedTime) {
      const candidate = new Date(`${selectedDate}T${selectedTime}`);
      const gapFree =
        !isNaN(candidate.getTime()) &&
        (() => {
          const back = instantToLocalParts(candidate.toISOString());
          return (
            back !== null &&
            back.date === selectedDate &&
            back.time === selectedTime
          );
        })();
      // Exact selected instant when it is a real local time; otherwise noon.
      instant = gapFree ? candidate : new Date(`${selectedDate}T12:00:00`);
    } else if (selectedDate) {
      instant = new Date(`${selectedDate}T12:00:00`);
    } else {
      instant = new Date();
    }
    if (isNaN(instant.getTime())) return "Local time";
    const offsetMinutes = -instant.getTimezoneOffset();
    const sign = offsetMinutes >= 0 ? "+" : "-";
    const abs = Math.abs(offsetMinutes);
    const hh = String(Math.floor(abs / 60)).padStart(2, "0");
    const mm = String(abs % 60).padStart(2, "0");
    const tzName =
      Intl.DateTimeFormat(undefined, { timeZoneName: "short" })
        .formatToParts(instant)
        .find((p) => p.type === "timeZoneName")?.value ?? "";
    return `${tzName} (UTC${sign}${hh}:${mm})`;
  } catch {
    return "Local time";
  }
}

interface DateTimeFieldProps {
  readonly label: string;
  readonly value: string;
  readonly onChange: (utcInstant: string) => void;
  readonly error?: string;
  readonly helperText?: string;
  readonly disabled?: boolean;
  readonly required?: boolean;
  readonly id?: string;
}

/**
 * Reusable date/time selection field displaying user-local time and emitting UTC instants.
 * Uses native date and time inputs for accessibility, keyboard navigation, and mobile support.
 *
 * Layout: flex-wrap with minimum widths (date ≥ 9rem, time ≥ 7.5rem) so inputs sit
 * side-by-side when the component has enough width and stack vertically in narrow
 * containers (nested grid columns, sidebar, mobile) without clipping native controls.
 *
 * Timezone/DST: the UTC offset label updates to reflect the selected date (not just
 * today). Nonexistent local times (DST spring-forward gaps) are rejected; ambiguous
 * fall-back times are accepted and resolved to the first occurrence.
 *
 * The value prop is a UTC instant string used for initial display; subsequent edits are
 * tracked internally and converted to UTC via the browser timezone before calling onChange.
 */
export function DateTimeField({
  label,
  value,
  onChange,
  error,
  helperText,
  disabled = false,
  required = false,
  id,
}: DateTimeFieldProps) {
  const autoId = useId();
  const fieldId = id ?? autoId;
  const errorId = `${fieldId}-error`;
  const helperId = `${fieldId}-helper`;
  const timeId = `${fieldId}-time`;

  // Track date and time independently; initialized from the value prop.
  // No prop-to-state sync after mount (parent remounts via key when context changes).
  const initial = instantToLocalParts(value);
  const [dateValue, setDateValue] = useState(initial?.date ?? "");
  const [timeValue, setTimeValue] = useState(initial?.time ?? "");

  const tz = timezoneLabel(dateValue || undefined, timeValue || undefined);

  function handleDateChange(newDate: string) {
    setDateValue(newDate);
    onChange(localPartsToInstant(newDate, timeValue) ?? "");
  }

  function handleTimeChange(newTime: string) {
    setTimeValue(newTime);
    onChange(localPartsToInstant(dateValue, newTime) ?? "");
  }

  return (
    <div className="space-y-1.5">
      <label
        className="text-sm font-semibold text-foreground-secondary"
        htmlFor={fieldId}
      >
        {label}
      </label>
      <div className="flex flex-wrap items-start gap-2">
        <input
          aria-describedby={
            [error ? errorId : null, helperText ? helperId : null]
              .filter(Boolean)
              .join(" ") || undefined
          }
          aria-invalid={error ? true : undefined}
          className={`${CONTROL_CLASSES} min-w-[9rem] flex-[2_1_9rem]`}
          disabled={disabled}
          id={fieldId}
          onChange={(e) => handleDateChange(e.target.value)}
          required={required}
          type="date"
          value={dateValue}
        />
        <input
          aria-label={`${label} time`}
          className={`${CONTROL_CLASSES} min-w-[7.5rem] flex-[1_1_7.5rem]`}
          disabled={disabled}
          id={timeId}
          onChange={(e) => handleTimeChange(e.target.value)}
          required={required}
          type="time"
          value={timeValue}
        />
      </div>
      <p className="text-xs text-foreground-muted" id={helperId}>
        {helperText ?? `Timezone: ${tz}`}
      </p>
      {error && (
        <p className="text-sm text-danger" id={errorId} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
