import type { RealtimeSchedule } from "../api/realtime-client";

/**
 * Bounded REST reconciliation driver: invalidation bursts coalesce into a single
 * trailing refresh, a minimum interval prevents refresh storms, an in-flight guard
 * collapses concurrent demands into one follow-up, and stale responses are left to the
 * views' existing sequence/abort fencing.
 */

export interface ResyncSchedulerOptions {
  readonly debounceMs?: number;
  readonly minimumIntervalMs?: number;
  readonly schedule?: RealtimeSchedule;
  readonly now?: () => number;
}

export type ResyncReason = "signal" | "periodic";

export interface ResyncScheduler {
  /** Event-driven request (invalidation, resync signal, visibility recovery). */
  request(): void;
  /** Periodic tick; uses the same coalescing and bounds. */
  tick(): void;
  dispose(): void;
}

export function createResyncScheduler(
  run: (reason: ResyncReason) => void | Promise<unknown>,
  options: ResyncSchedulerOptions = {},
): ResyncScheduler {
  const debounceMs = options.debounceMs ?? 500;
  const minimumIntervalMs = options.minimumIntervalMs ?? 2_000;
  const schedule =
    options.schedule ??
    ((callback, milliseconds) => {
      const timer = setTimeout(callback, milliseconds);
      return () => clearTimeout(timer);
    });
  const now = options.now ?? Date.now;

  let timer: (() => void) | null = null;
  let inFlight = false;
  let pendingAfterInFlight = false;
  let lastRunAt: number | null = null;
  let disposed = false;

  const execute = (reason: ResyncReason) => {
    timer?.();
    timer = null;
    if (disposed) return;
    if (inFlight) {
      pendingAfterInFlight = true;
      return;
    }
    inFlight = true;
    lastRunAt = now();
    let released = false;
    const release = () => {
      if (released) return;
      released = true;
      inFlight = false;
      if (disposed) return;
      if (pendingAfterInFlight) {
        pendingAfterInFlight = false;
        scheduleNext(debounceMs);
      }
    };
    try {
      const result = run(reason);
      if (result && typeof (result as Promise<unknown>).then === "function") {
        void (result as Promise<unknown>).then(release, release);
      } else {
        release();
      }
    } catch {
      release();
    }
  };

  const scheduleNext = (delayMs: number, reason: ResyncReason = "signal") => {
    timer?.();
    timer = schedule(() => execute(reason), delayMs);
  };

  const request = () => {
    if (disposed) return;
    if (lastRunAt !== null) {
      const sinceLast = now() - lastRunAt;
      if (sinceLast < minimumIntervalMs) {
        const remaining = minimumIntervalMs - sinceLast + debounceMs;
        if (timer === null) scheduleNext(remaining);
        return;
      }
    }
    scheduleNext(debounceMs);
  };

  return {
    request,
    tick: () => {
      if (disposed) return;
      if (lastRunAt !== null && now() - lastRunAt < minimumIntervalMs) return;
      scheduleNext(debounceMs, "periodic");
    },
    dispose() {
      disposed = true;
      pendingAfterInFlight = false;
      timer?.();
      timer = null;
    },
  };
}
