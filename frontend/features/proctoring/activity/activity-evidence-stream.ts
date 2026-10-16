import type { MonitoringApiClient } from "../api/monitoring-api-client";
import { isUncertainProctoringFailure } from "../api/proctoring-request";
import {
  isQuestionScoped,
  validateBrowserEvent,
  type BrowserEventInput,
  type BuiltBrowserEvent,
  type MonitoringSession,
} from "../model/monitoring";
import { nowIso, randomUuid } from "../utils/identity";

export type ActivityDeliveryState =
  "idle" | "delivering" | "uncertain" | "blocked" | "stopped";

export interface ActivityEvidenceSnapshot {
  readonly delivery: ActivityDeliveryState;
  readonly pendingCount: number;
  readonly droppedCount: number;
  readonly lastAcknowledgedOrdinal: number | null;
}

export interface ActivityEvidenceStream {
  readonly session: MonitoringSession;
  getSnapshot(): ActivityEvidenceSnapshot;
  subscribe(listener: () => void): () => void;
  observe(input: BrowserEventInput): boolean;
  reconcile(): Promise<void>;
  stop(options?: { readonly reconcile?: boolean }): Promise<void>;
}

interface QueuedEvent {
  readonly event: BuiltBrowserEvent;
}

const DEFAULT_QUEUE_LIMIT = 64;

export function createActivityEvidenceStream(options: {
  readonly api: MonitoringApiClient;
  readonly session: MonitoringSession;
  readonly getSessionGeneration: () => number | null;
  readonly sessionGeneration: number;
  readonly queueLimit?: number;
  readonly createUuid?: () => string;
  readonly clock?: () => number;
}): ActivityEvidenceStream {
  const {
    api,
    session,
    getSessionGeneration,
    sessionGeneration,
    queueLimit = DEFAULT_QUEUE_LIMIT,
    createUuid = randomUuid,
    clock = Date.now,
  } = options;
  const streamId = createUuid();
  const listeners = new Set<() => void>();
  const queue: QueuedEvent[] = [];
  let sequence = 0;
  let stopped = false;
  let draining = false;
  let droppedCount = 0;
  let lastAcknowledgedOrdinal: number | null = null;
  let delivery: ActivityDeliveryState = "idle";
  let snapshot: ActivityEvidenceSnapshot = {
    delivery,
    pendingCount: 0,
    droppedCount,
    lastAcknowledgedOrdinal,
  };

  const publish = () => {
    snapshot = {
      delivery,
      pendingCount: queue.length,
      droppedCount,
      lastAcknowledgedOrdinal,
    };
    listeners.forEach((listener) => listener());
  };
  const setDelivery = (next: ActivityDeliveryState) => {
    delivery = next;
    publish();
  };
  const sameSession = () =>
    getSessionGeneration() === sessionGeneration && !stopped;

  async function drain(allowUncertainReplay = false): Promise<void> {
    if (draining || !sameSession() || queue.length === 0) return;
    if (delivery === "blocked") return;
    if (delivery === "uncertain" && !allowUncertainReplay) return;

    draining = true;
    setDelivery("delivering");
    try {
      while (sameSession() && queue.length > 0) {
        const current = queue[0]!;
        const result = await api.appendEvent(session.id, current.event);
        if (!sameSession()) return;
        if (result.ok) {
          queue.shift();
          lastAcknowledgedOrdinal = result.value.ordinal;
          publish();
          continue;
        }
        if (isUncertainProctoringFailure(result.error)) {
          setDelivery("uncertain");
          return;
        }
        queue.splice(0);
        stopped = true;
        setDelivery("blocked");
        return;
      }
      setDelivery(queue.length === 0 ? "idle" : "blocked");
    } finally {
      draining = false;
    }
  }

  const stream: ActivityEvidenceStream = {
    session,
    getSnapshot: () => snapshot,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    observe(input) {
      if (!sameSession() || session.status !== "ACTIVE") return false;
      if (queue.length >= queueLimit) {
        droppedCount += 1;
        publish();
        return false;
      }
      const event: BuiltBrowserEvent = {
        eventId: createUuid(),
        streamId,
        sequence,
        type: input.type,
        observedAt:
          input.observedAt === undefined ? nowIso(clock) : input.observedAt,
        questionNumber: isQuestionScoped(input.type)
          ? (input.questionNumber ?? null)
          : null,
        schemaVersion: 1,
      };
      if (!validateBrowserEvent(event)) return false;
      sequence += 1;
      queue.push({ event });
      publish();
      void drain();
      return true;
    },
    async reconcile() {
      if (!sameSession()) return;
      const result = await api.reconcile(session.id);
      if (!sameSession()) return;
      if (!result.ok) {
        setDelivery(
          isUncertainProctoringFailure(result.error) ? "uncertain" : "blocked",
        );
        return;
      }
      if (result.value.status === "ENDED") {
        queue.splice(0);
        stopped = true;
        setDelivery("stopped");
        return;
      }
      await drain(true);
    },
    async stop(stopOptions) {
      if (stopped) return;
      if (stopOptions?.reconcile === true) await stream.reconcile();
      stopped = true;
      queue.splice(0);
      setDelivery("stopped");
      listeners.clear();
    },
  };

  return stream;
}
