"use client";

import {
  forwardRef,
  useId,
  useRef,
  type ClipboardEvent,
  type KeyboardEvent,
} from "react";

interface OtpInputProps {
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly onBlur?: () => void;
  readonly disabled?: boolean;
  readonly error?: string;
}

const DIGITS = 6;

export const OtpInput = forwardRef<HTMLInputElement, OtpInputProps>(
  function OtpInput(
    { value, onChange, onBlur, disabled, error },
    forwardedRef,
  ) {
    const id = useId();
    const inputs = useRef<(HTMLInputElement | null)[]>([]);

    function focus(index: number) {
      const input = inputs.current[Math.max(0, Math.min(DIGITS - 1, index))];
      input?.focus();
      input?.select();
    }

    function write(index: number, raw: string) {
      const start = Math.min(index, value.length);
      const digits = raw.replace(/[^0-9]/g, "").slice(0, DIGITS - start);
      if (!digits) return;
      const slots = Array.from(
        { length: DIGITS },
        (_, position) => value[position] ?? "",
      );
      for (let offset = 0; offset < digits.length; offset += 1) {
        slots[start + offset] = digits[offset] ?? "";
      }
      onChange(slots.join(""));
      focus(start + digits.length);
    }

    function handleKeyDown(
      event: KeyboardEvent<HTMLInputElement>,
      index: number,
    ) {
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault();
        focus(index + (event.key === "ArrowLeft" ? -1 : 1));
      } else if (event.key === "Backspace" || event.key === "Delete") {
        event.preventDefault();
        if (value[index]) {
          onChange(value.slice(0, index) + value.slice(index + 1));
          focus(index);
        } else if (event.key === "Backspace" && index > 0) {
          const previous = index - 1;
          onChange(value.slice(0, previous) + value.slice(index));
          focus(previous);
        }
      }
    }

    function handlePaste(
      event: ClipboardEvent<HTMLInputElement>,
      index: number,
    ) {
      event.preventDefault();
      write(index, event.clipboardData.getData("text"));
    }

    return (
      <fieldset className="min-w-0 space-y-2" disabled={disabled}>
        <legend className="text-sm font-semibold text-foreground-secondary">
          Verification code
        </legend>
        <div
          className="grid grid-cols-6 gap-1.5 sm:gap-2.5"
          role="group"
          aria-label="Verification code"
        >
          {Array.from({ length: DIGITS }, (_, index) => (
            <input
              aria-describedby={error ? `${id}-error` : undefined}
              aria-invalid={error ? true : undefined}
              aria-label={`Verification code digit ${index + 1} of ${DIGITS}`}
              autoCapitalize="off"
              autoComplete={index === 0 ? "one-time-code" : "off"}
              className={`min-h-12 min-w-0 w-full rounded-xl border bg-surface p-0 text-center text-xl font-semibold tabular-nums text-foreground shadow-sm transition-[border-color,box-shadow] focus-visible:border-focus focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus/30 disabled:cursor-not-allowed disabled:bg-surface-muted motion-reduce:transition-none ${error ? "border-danger" : "border-border-strong"}`}
              inputMode="numeric"
              key={index}
              maxLength={index === 0 ? DIGITS : 1}
              onBlur={onBlur}
              onChange={(event) => write(index, event.target.value)}
              onKeyDown={(event) => handleKeyDown(event, index)}
              onPaste={(event) => handlePaste(event, index)}
              pattern="[0-9]*"
              ref={(node) => {
                inputs.current[index] = node;
                if (index === 0) {
                  if (typeof forwardedRef === "function") forwardedRef(node);
                  else if (forwardedRef) forwardedRef.current = node;
                }
              }}
              spellCheck={false}
              type="text"
              value={value[index] ?? ""}
            />
          ))}
        </div>
        {error ? (
          <p
            className="text-sm font-medium text-danger"
            id={`${id}-error`}
            role="alert"
          >
            {error}
          </p>
        ) : null}
      </fieldset>
    );
  },
);

OtpInput.displayName = "OtpInput";
