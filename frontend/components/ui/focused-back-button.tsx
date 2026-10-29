"use client";

import { Button } from "./button";

/**
 * Shared icon-only back control for focused workspace headers (Quiz Editor,
 * Student Attempt). One canonical geometry — 44×44px circular secondary
 * button with a chevron — so both dedicated headers stay visually identical.
 * The accessible label is localized by the caller; navigation confirmation
 * guards stay in the caller's onClick.
 */
export function FocusedBackButton({
  ariaLabel,
  disabled = false,
  onClick,
}: {
  readonly ariaLabel: string;
  readonly disabled?: boolean;
  readonly onClick: () => void;
}) {
  return (
    <Button
      aria-label={ariaLabel}
      className="size-11 shrink-0 p-0"
      disabled={disabled}
      onClick={onClick}
      variant="secondary"
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
    </Button>
  );
}
