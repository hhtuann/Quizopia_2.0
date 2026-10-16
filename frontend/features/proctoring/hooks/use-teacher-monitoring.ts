"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { ProctoringFailure } from "../api/proctoring-request";
import type {
  MonitoringEvidence,
  MonitoringSession,
} from "../model/monitoring";
import { useProctoringClients } from "./use-proctoring-clients";

export type TeacherMonitoringState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | {
      readonly status: "ready";
      readonly session: MonitoringSession;
      readonly timeline: readonly MonitoringEvidence[];
      readonly nextAfter: number | null;
      readonly loadingMore: boolean;
    }
  | { readonly status: "not-found" }
  | { readonly status: "unavailable"; readonly error: ProctoringFailure };

export function useTeacherMonitoring(initialAttemptId?: string) {
  const clients = useProctoringClients();
  const [state, setState] = useState<TeacherMonitoringState>({
    status: "idle",
  });
  const request = useRef(0);

  const lookup = useCallback(
    async (attemptId: string) => {
      request.current += 1;
      const current = request.current;
      if (clients === null) {
        setState({ status: "unavailable", error: { kind: "session" } });
        return;
      }
      setState({ status: "loading" });
      const sessionResult = await clients.monitoring.findByAttempt(attemptId);
      if (current !== request.current) return;
      if (!sessionResult.ok) {
        if (
          sessionResult.error.kind === "api" &&
          sessionResult.error.status === 404
        ) {
          setState({ status: "not-found" });
        } else {
          setState({ status: "unavailable", error: sessionResult.error });
        }
        return;
      }
      const timelineResult = await clients.monitoring.timeline(
        sessionResult.value.id,
      );
      if (current !== request.current) return;
      if (!timelineResult.ok) {
        setState({ status: "unavailable", error: timelineResult.error });
        return;
      }
      setState({
        status: "ready",
        session: sessionResult.value,
        timeline: timelineResult.value.items,
        nextAfter: timelineResult.value.nextAfter,
        loadingMore: false,
      });
    },
    [clients],
  );

  const loadMore = useCallback(async () => {
    if (
      clients === null ||
      state.status !== "ready" ||
      state.nextAfter === null
    )
      return;
    const session = state.session;
    const after = state.nextAfter;
    setState({ ...state, loadingMore: true });
    const result = await clients.monitoring.timeline(session.id, { after });
    if (!result.ok) {
      setState({ status: "unavailable", error: result.error });
      return;
    }
    setState((current) => {
      if (current.status !== "ready" || current.session.id !== session.id)
        return current;
      const existing = new Set(current.timeline.map((item) => item.ordinal));
      return {
        ...current,
        timeline: [
          ...current.timeline,
          ...result.value.items.filter((item) => !existing.has(item.ordinal)),
        ],
        nextAfter: result.value.nextAfter,
        loadingMore: false,
      };
    });
  }, [clients, state]);

  const refresh = useCallback(async () => {
    if (state.status === "ready") await lookup(state.session.attemptId);
  }, [lookup, state]);

  useEffect(() => {
    if (initialAttemptId !== undefined) {
      queueMicrotask(() => void lookup(initialAttemptId));
    }
    return () => {
      request.current += 1;
    };
  }, [initialAttemptId, lookup]);

  return { state, lookup, loadMore, refresh };
}
