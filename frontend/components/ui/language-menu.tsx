"use client";

import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { useI18n } from "../../lib/i18n/provider";
import type { Locale } from "../../lib/i18n/core";
import { CheckIcon } from "./icons";

const localeNames: Record<Locale, string> = {
  vi: "Tiếng Việt",
  en: "English",
};

/**
 * Compact language popover for dedicated workspaces and public chrome where a
 * standalone select dropdown is not wanted. One trigger button; options are
 * menuitemradio entries with a checked indicator. Mirrors the account-menu
 * keyboard contract: ArrowUp/Down move focus, Escape closes and restores
 * focus to the trigger, outside pointer closes. Switching is immediate and
 * persisted through the shared i18n provider (no remounts).
 */
export function LanguageMenu({
  align = "right",
}: {
  readonly align?: "left" | "right";
}) {
  const { locale, setLocale } = useI18n();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuId = useId();

  useEffect(() => {
    if (!open) return;
    menuRef.current
      ?.querySelector<HTMLButtonElement>(
        '[role="menuitemradio"]:not(:disabled)',
      )
      ?.focus();
    function closeOnOutsidePointer(event: PointerEvent) {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("pointerdown", closeOnOutsidePointer);
    return () =>
      document.removeEventListener("pointerdown", closeOnOutsidePointer);
  }, [open]);

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      setOpen(false);
      triggerRef.current?.focus();
      return;
    }
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    event.preventDefault();
    const items = Array.from(
      event.currentTarget.querySelectorAll<HTMLButtonElement>(
        '[role="menuitemradio"]:not(:disabled)',
      ),
    );
    const current = items.indexOf(document.activeElement as HTMLButtonElement);
    const delta = event.key === "ArrowDown" ? 1 : -1;
    const next =
      current < 0 ? 0 : (current + delta + items.length) % items.length;
    items[next]?.focus();
  }

  const itemClasses =
    "flex min-h-11 w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm font-medium text-foreground-secondary transition-colors hover:bg-primary/5 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus motion-reduce:transition-none";

  return (
    <div className="relative shrink-0" ref={rootRef}>
      <button
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={`${locale === "vi" ? "Ngôn ngữ" : "Language"}: ${localeNames[locale]}`}
        className="flex min-h-11 items-center gap-1.5 rounded-xl border border-border bg-surface px-3 text-sm font-semibold text-foreground-secondary transition-colors hover:border-primary/30 hover:bg-primary/5 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none"
        onClick={() => setOpen((value) => !value)}
        ref={triggerRef}
        type="button"
      >
        <svg
          aria-hidden="true"
          className="size-4"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          viewBox="0 0 24 24"
        >
          <circle cx="12" cy="12" r="9" />
          <path d="M3 12h18M12 3a15 15 0 0 1 0 18M12 3a15 15 0 0 0 0 18" />
        </svg>
        <span className="uppercase">{locale}</span>
      </button>
      {open ? (
        <div
          aria-label={locale === "vi" ? "Ngôn ngữ" : "Language"}
          className={`absolute z-50 mt-2 w-44 rounded-xl border border-border bg-surface p-2 shadow-card-hover ${
            align === "right" ? "right-0" : "left-0"
          }`}
          id={menuId}
          onKeyDown={handleKeyDown}
          ref={menuRef}
          role="menu"
        >
          {(["vi", "en"] as const).map((option) => (
            <button
              aria-checked={locale === option}
              className={itemClasses}
              key={option}
              lang={option}
              onClick={() => {
                setLocale(option);
                setOpen(false);
                triggerRef.current?.focus();
              }}
              role="menuitemradio"
              type="button"
            >
              <CheckIcon
                aria-hidden="true"
                className={`size-4 shrink-0 text-primary ${locale === option ? "" : "invisible"}`}
              />
              {localeNames[option]}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
