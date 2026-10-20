"use client";

import type { RealtimeStatus } from "../hooks/use-realtime-subscriptions";

const LABELS: Record<string, string> = {
  connecting: "connecting…",
  authenticating: "signing in…",
  authenticated: "connected",
  "reconnect-wait": "reconnecting…",
  stopped: "unavailable",
  idle: "off",
};

/** Truthful, minimal live-update indicator; never reflects or contains credentials. */
export function RealtimeStatusInline({
  status,
}: {
  readonly status: RealtimeStatus;
}) {
  if (status.state === "idle") return null;
  const label = LABELS[status.state] ?? status.state;
  return (
    <span
      className="text-xs text-foreground-muted"
      data-realtime-state={status.state}
      title="Live update hints are best-effort; server state stays authoritative."
    >
      Live updates: {label}
      {status.state === "reconnect-wait" && status.attempts > 0
        ? ` (attempt ${status.attempts})`
        : ""}
    </span>
  );
}
