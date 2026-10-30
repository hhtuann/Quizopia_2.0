"use client";

import { useId, useState } from "react";
import { useI18n } from "../../../lib/i18n/provider";

/**
 * Compact masked PRIVATE locator field: hidden by default (•••), an
 * icon-only eye toggle reveals the exact locator, an icon-only copy button
 * copies it. Clipboard feedback is announced politely. The value never
 * enters URLs, logs or telemetry; owner-only server authorization is
 * unchanged (the caller only renders this for the owner view).
 */
export function PrivatePublicationLocator({
  locator,
}: {
  readonly locator: string;
}) {
  const { t } = useI18n();
  const id = useId();
  const [revealed, setRevealed] = useState(false);
  const [status, setStatus] = useState<"idle" | "copied" | "failed">("idle");
  async function copy() {
    try {
      await navigator.clipboard.writeText(locator);
      setStatus("copied");
    } catch {
      setStatus("failed");
    }
  }
  const iconButton =
    "flex size-11 shrink-0 items-center justify-center rounded-lg text-foreground-secondary transition-colors hover:bg-primary/5 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus motion-reduce:transition-none";
  return (
    <div className="min-w-0">
      <p
        className="text-sm font-semibold text-foreground-secondary"
        id={`${id}-label`}
      >
        {t("Private locator")}
      </p>
      <div className="mt-1.5 flex min-w-0 items-center rounded-xl border border-border bg-surface-muted p-1">
        <output
          aria-labelledby={`${id}-label`}
          aria-live="polite"
          className="relative min-w-0 flex-1 truncate px-2 font-mono text-sm text-foreground"
          id={`${id}-value`}
        >
          {revealed ? (
            <span className="select-all">{locator}</span>
          ) : (
            <span aria-hidden="true">{"•".repeat(12)}</span>
          )}
          <span className="sr-only left-0 top-0">
            {revealed
              ? t("Private locator revealed")
              : t("Private locator hidden")}
          </span>
        </output>
        <button
          aria-controls={`${id}-value`}
          aria-label={revealed ? t("Hide locator") : t("Show locator")}
          aria-pressed={revealed}
          className={iconButton}
          onClick={() => setRevealed((value) => !value)}
          type="button"
        >
          {revealed ? (
            <svg
              aria-hidden="true"
              className="size-5"
              fill="none"
              stroke="currentColor"
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth="2"
              viewBox="0 0 24 24"
            >
              <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 10 4 10 8a12.3 12.3 0 0 1-3 4" />
              <path d="M6.1 6.1C3.6 7.7 2 10 2 12c0 4 4 8 10 8 1.8 0 3.4-.3 4.8-.9" />
              <path d="m2 2 20 20" />
            </svg>
          ) : (
            <svg
              aria-hidden="true"
              className="size-5"
              fill="none"
              stroke="currentColor"
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth="2"
              viewBox="0 0 24 24"
            >
              <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z" />
              <circle cx="12" cy="12" r="3" />
            </svg>
          )}
        </button>
        <button
          aria-label={t("Copy locator")}
          className={iconButton}
          onClick={() => void copy()}
          type="button"
        >
          <svg
            aria-hidden="true"
            className="size-5"
            fill="none"
            stroke="currentColor"
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth="2"
            viewBox="0 0 24 24"
          >
            <rect height="4" rx="1" width="16" x="4" y="16" />
            <path d="M4 16V6a2 2 0 0 1 2-2h8l6 6v6" />
            <path d="M14 4v6h6" />
          </svg>
        </button>
      </div>
      <p className="mt-1.5 text-xs" role="status">
        {status === "copied"
          ? t("Copied.")
          : status === "failed"
            ? t("Copy failed. Reveal the locator and copy it manually.")
            : t("Share only with students you want to admit.")}
      </p>
    </div>
  );
}
