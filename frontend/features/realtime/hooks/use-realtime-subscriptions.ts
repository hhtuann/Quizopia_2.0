"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  RealtimeCredentialProvider,
  RealtimeClientEvent,
  RealtimeConnectionState,
  RealtimeSchedule,
  RealtimeSignalEvent,
  RealtimeStopReason,
} from "../api/realtime-client";
import { RealtimeClient } from "../api/realtime-client";
import {
  realtimeWebSocketUrl,
  type RealtimeTransport,
} from "../api/realtime-socket";
import { createRealtimeTicketRequester } from "../api/realtime-ticket-client";
import type { RealtimeResource } from "../model/realtime-frame";
import { realtimeFeatureEnabled } from "../model/realtime-flag";
import { useAuth } from "../../auth/auth-provider";
import { createResyncScheduler } from "./resync-scheduler";

export interface RealtimeStatus {
  readonly state: RealtimeConnectionState;
  readonly attempts: number;
  readonly stopReason: RealtimeStopReason;
}

export const REALTIME_STATUS_OFF: RealtimeStatus = Object.freeze({
  state: "idle",
  attempts: 0,
  stopReason: null,
});

/**
 * Auth-fenced realtime subscriptions for the current view. The client lifecycle is bound
 * to the authenticated executor and session generation: logout, identity change or token
 * teardown stops the socket and clears state; visibility/online recovery restarts with a
 * fresh ticket and the current token. Realtime never becomes an auth store.
 */
export function useRealtimeSubscriptions(options: {
  readonly enabled: boolean;
  readonly resources: readonly RealtimeResource[];
  readonly onSignal: (signal: RealtimeSignalEvent) => void;
  readonly transport?: RealtimeTransport;
  readonly apiBaseUrl?: string;
  readonly schedule?: RealtimeSchedule;
}): RealtimeStatus {
  const { authenticatedRequests, getSessionGeneration, getAccessToken, user } =
    useAuth();
  const generation = getSessionGeneration();
  const active =
    options.enabled &&
    realtimeFeatureEnabled() &&
    authenticatedRequests !== null &&
    user !== null;

  const onSignalRef = useRef(options.onSignal);
  useEffect(() => {
    onSignalRef.current = options.onSignal;
  }, [options.onSignal]);

  const [status, setStatus] = useState<RealtimeStatus>(REALTIME_STATUS_OFF);
  const clientRef = useRef<RealtimeClient | null>(null);
  const resourcesRef = useRef(options.resources);
  useEffect(() => {
    resourcesRef.current = options.resources;
  });

  useEffect(() => {
    if (!active || generation === null) {
      return;
    }
    const executor = authenticatedRequests;
    const boundGeneration = generation;
    const requester = createRealtimeTicketRequester({
      authenticatedRequests: executor,
      apiBaseUrl: options.apiBaseUrl,
    });
    const url = realtimeWebSocketUrl(options.apiBaseUrl);
    const credentialsProvider: RealtimeCredentialProvider = async () => {
      if (getSessionGeneration() !== boundGeneration)
        return { ok: false, error: "session" };
      // The requester pins the exact vault token for ticket issuance and returns it as
      // part of the bound pair: whatever concurrent rotation happens around the request,
      // the AUTH frame carries the precise JWT the ticket was issued under. A stale pin
      // is recovered once through the refresh-aware executor and re-pinned.
      const bound = await requester.requestBoundTicket({ getAccessToken });
      if (getSessionGeneration() !== boundGeneration)
        return { ok: false, error: "session" };
      if (!bound.ok) {
        return bound.error.kind === "session"
          ? { ok: false, error: "session" }
          : { ok: false, error: "retryable" };
      }
      return {
        ok: true,
        ticket: bound.ticket,
        accessToken: bound.accessToken,
      };
    };
    const client = new RealtimeClient({
      credentialsProvider,
      url,
      transport: options.transport,
      schedule: options.schedule,
      onEvent: (event: RealtimeClientEvent) => {
        if (event.type === "state") {
          if (getSessionGeneration() !== boundGeneration) return;
          setStatus({
            state: event.state,
            attempts: event.attempts,
            stopReason: event.stopReason,
          });
          // A successful (re)connection closes the gap window in which hints may have
          // been lost or reordered: reconcile authoritative REST state immediately even
          // when no invalidation hint arrives.
          if (event.state === "authenticated") {
            onSignalRef.current({ type: "connected" });
          }
          return;
        }
        if (getSessionGeneration() !== boundGeneration) return;
        onSignalRef.current(event);
      },
    });
    clientRef.current = client;
    client.setResources(resourcesRef.current);
    client.start();
    const reconnectOnRecovery = () => {
      if (document.visibilityState !== "visible" || !navigator.onLine) return;
      client.restart();
      // Visibility/online recovery may have missed hints while hidden or offline even on
      // a still-open connection: request an authoritative REST reconciliation too.
      if (getSessionGeneration() === boundGeneration) {
        onSignalRef.current({ type: "connected" });
      }
    };
    document.addEventListener("visibilitychange", reconnectOnRecovery);
    window.addEventListener("online", reconnectOnRecovery);
    return () => {
      document.removeEventListener("visibilitychange", reconnectOnRecovery);
      window.removeEventListener("online", reconnectOnRecovery);
      clientRef.current = null;
      client.stop();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    active,
    generation,
    options.apiBaseUrl,
    options.transport,
    options.schedule,
  ]);

  const setResources = useCallback((next: readonly RealtimeResource[]) => {
    clientRef.current?.setResources(next);
  }, []);

  useEffect(() => {
    setResources(options.resources);
  }, [options.resources, setResources]);

  return active ? status : REALTIME_STATUS_OFF;
}

/**
 * Coalesced, bounded REST reconciliation: realtime signals request a debounced refresh,
 * a minimum interval and in-flight guard prevent storms, and a bounded periodic resync
 * runs while the view is active and visible. The provided refresh goes through the
 * views' existing abort/sequence fencing; realtime never mutates authoritative state.
 */
export function useRealtimeResync(options: {
  readonly active: boolean;
  readonly run: () => void | Promise<unknown>;
  readonly periodicRun?: () => void | Promise<unknown>;
  readonly periodMs?: number;
}): { readonly request: () => void } {
  const runRef = useRef(options.run);
  const periodicRunRef = useRef(options.periodicRun);
  useEffect(() => {
    runRef.current = options.run;
    periodicRunRef.current = options.periodicRun;
  });
  const schedulerRef = useRef<ReturnType<typeof createResyncScheduler> | null>(
    null,
  );

  useEffect(() => {
    const scheduler = createResyncScheduler((reason) => {
      // Return the actual refresh Promise: the scheduler's in-flight guard must hold
      // until the REST request settles, so overlapping signals and periodic ticks
      // coalesce instead of starting concurrent refreshes.
      const run =
        reason === "periodic"
          ? (periodicRunRef.current ?? runRef.current)
          : runRef.current;
      return run();
    }, {});
    schedulerRef.current = scheduler;
    return () => {
      schedulerRef.current = null;
      scheduler.dispose();
    };
  }, [runRef, periodicRunRef]);

  const request = useCallback(() => schedulerRef.current?.request(), []);
  const requestRef = useRef(request);
  useEffect(() => {
    requestRef.current = request;
  });

  const periodMs = options.periodMs ?? 60_000;
  useEffect(() => {
    if (!options.active || periodMs <= 0) return;
    const interval = setInterval(() => {
      if (document.visibilityState === "visible") schedulerRef.current?.tick();
    }, periodMs);
    return () => clearInterval(interval);
  }, [options.active, periodMs]);

  return useMemo(() => ({ request: () => requestRef.current() }), []);
}
