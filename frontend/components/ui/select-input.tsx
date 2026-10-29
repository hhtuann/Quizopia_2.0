"use client";

import { useId, type ReactNode, type SelectHTMLAttributes } from "react";
import { ChevronDownIcon } from "./icons";

export interface SelectInputProps extends SelectHTMLAttributes<HTMLSelectElement> {
  readonly children: ReactNode;
}

/**
 * Native select with a shared custom chevron.
 *
 * Chromium renders the UA dropdown arrow at the intrinsic right edge of a
 * native select; `padding-right` moves only the text area, never the arrow,
 * so a CSS-only fix cannot satisfy the 12–16px chevron-to-border spacing.
 * This wrapper removes the UA decoration (`appearance-none`) and positions a
 * decorative icon 12px from the right border inside a relative container,
 * keeping native behavior: keyboard access, focus ring on the select,
 * disabled semantics and the caller's accessible label are untouched.
 */
export function SelectInput({
  className = "",
  children,
  disabled,
  id,
  ...props
}: SelectInputProps) {
  const autoId = useId();
  const selectId = id ?? autoId;
  return (
    <span className="relative inline-flex min-w-0 max-w-full">
      <select
        {...props}
        className={`min-h-11 w-full cursor-pointer rounded-xl border border-border bg-surface py-2.5 pl-3 pr-9 text-sm text-foreground appearance-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus disabled:cursor-not-allowed disabled:bg-surface-muted disabled:text-foreground-disabled ${className}`}
        disabled={disabled}
        id={selectId}
      >
        {children}
      </select>
      <ChevronDownIcon
        aria-hidden="true"
        className="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-foreground-muted"
      />
    </span>
  );
}
