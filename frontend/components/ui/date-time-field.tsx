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
 * Returns null when either part is empty or the combination is invalid.
 */
export function localPartsToInstant(date: string, time: string): string | null {
  if (!date || !time) return null;
  const d = new Date(`${date}T${time}`);
  if (isNaN(d.getTime())) return null;
  return d.toISOString();
}

/**
 * Returns a user-friendly timezone label with UTC offset.
 */
export function timezoneLabel(): string {
  try {
    const offsetMinutes = -new Date().getTimezoneOffset();
    const sign = offsetMinutes >= 0 ? "+" : "-";
    const abs = Math.abs(offsetMinutes);
    const hh = String(Math.floor(abs / 60)).padStart(2, "0");
    const mm = String(abs % 60).padStart(2, "0");
    const tzName =
      Intl.DateTimeFormat(undefined, { timeZoneName: "short" })
        .formatToParts()
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

  const tz = timezoneLabel();

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
      <div className="flex flex-col gap-2 min-[420px]:flex-row min-[420px]:gap-2">
        <input
          aria-describedby={
            [error ? errorId : null, helperText ? helperId : null]
              .filter(Boolean)
              .join(" ") || undefined
          }
          aria-invalid={error ? true : undefined}
          className={`${CONTROL_CLASSES} min-[420px]:min-w-0 min-[420px]:flex-[2]`}
          disabled={disabled}
          id={fieldId}
          onChange={(e) => handleDateChange(e.target.value)}
          required={required}
          type="date"
          value={dateValue}
        />
        <input
          aria-label={`${label} time`}
          className={`${CONTROL_CLASSES} min-[420px]:min-w-[7.5rem] min-[420px]:flex-1`}
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
