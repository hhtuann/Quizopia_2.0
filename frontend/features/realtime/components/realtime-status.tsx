"use client";

import { useI18n } from "../../../lib/i18n/provider";
import type { RealtimeStatus } from "../hooks/use-realtime-subscriptions";

const STATE_KEYS = {
  connecting: "connecting…",
  authenticating: "signing in…",
  authenticated: "connected",
  "reconnect-wait": "reconnecting…",
  stopped: "unavailable",
  idle: "off",
} as const;

/** Truthful, minimal live-update indicator; never reflects or contains credentials. */
export function RealtimeStatusInline({
  status,
}: {
  readonly status: RealtimeStatus;
}) {
  const { t } = useI18n();
  if (status.state === "idle") return null;
  const label = t(STATE_KEYS[status.state as keyof typeof STATE_KEYS]);
  const text =
    status.state === "reconnect-wait" && status.attempts > 0
      ? t("Live updates: {state} (attempt {count})", {
          state: label,
          count: status.attempts,
        })
      : t("Live updates: {state}", { state: label });
  return (
    <span
      className="text-xs text-foreground-muted"
      data-realtime-state={status.state}
      title={t(
        "Live update hints are best-effort; server state stays authoritative.",
      )}
    >
      {text}
    </span>
  );
}
