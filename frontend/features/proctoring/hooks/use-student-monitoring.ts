"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { attachBrowserActivityObserver } from "../activity/browser-activity-observer";
import {
  createActivityEvidenceStream,
  type ActivityEvidenceStream,
} from "../activity/activity-evidence-stream";
import type { ProctoringFailure } from "../api/proctoring-request";
import {
  evaluateMonitoringEligibility,
  type MonitoringEligibility,
} from "../model/eligibility";
import type { MonitoringSession } from "../model/monitoring";
import { useProctoringClients } from "./use-proctoring-clients";

export type StudentMonitoringState =
  | { readonly status: "idle" }
  | { readonly status: "starting" }
  | {
      readonly status: "active";
      readonly session: MonitoringSession;
      readonly activity: ActivityEvidenceStream;
    }
  | { readonly status: "ineligible"; readonly reason: string }
  | { readonly status: "unavailable"; readonly error: ProctoringFailure }
  | { readonly status: "ended" };

export interface StudentMonitoringController {
  readonly state: StudentMonitoringState;
  start(eligibility: MonitoringEligibility): Promise<void>;
  verifyAndStart(attemptId: string): Promise<void>;
  stop(): Promise<void>;
  questionViewed(questionNumber: number): boolean;
  questionLeft(questionNumber: number): boolean;
  answerSelected(questionNumber: number): boolean;
  answerChanged(questionNumber: number): boolean;
  answerCleared(questionNumber: number): boolean;
}

interface ActiveMonitoring {
  readonly stream: ActivityEvidenceStream;
  readonly detach: () => void;
  readonly unsubscribe: () => void;
  readonly deadlineTimer: ReturnType<typeof setTimeout>;
  readonly generation: number;
}

export function useStudentMonitoring(): StudentMonitoringController {
  const clients = useProctoringClients();
  const [state, setState] = useState<StudentMonitoringState>({
    status: "idle",
  });
  const activeRef = useRef<ActiveMonitoring | null>(null);
  const pendingRequest = useRef<AbortController | null>(null);
  const operation = useRef(0);
  const mounted = useRef(true);

  const cleanupActive = useCallback(async (reconcile: boolean) => {
    const active = activeRef.current;
    activeRef.current = null;
    if (active === null) return;
    clearTimeout(active.deadlineTimer);
    active.unsubscribe();
    active.detach();
    await active.stream.stop({ reconcile });
  }, []);

  const release = useCallback(
    async (reconcile: boolean) => {
      operation.current += 1;
      pendingRequest.current?.abort();
      pendingRequest.current = null;
      await cleanupActive(reconcile);
    },
    [cleanupActive],
  );

  useEffect(() => {
    mounted.current = true;
    const active = activeRef.current;
    if (active !== null && active.generation !== clients?.generation) {
      void release(false);
      setState({ status: "idle" });
    }
    return () => {
      mounted.current = false;
      void release(false);
    };
  }, [clients?.generation, release]);

  const verifyAndStart = useCallback(
    async (attemptId: string) => {
      const currentOperation = operation.current + 1;
      operation.current = currentOperation;
      pendingRequest.current?.abort();
      const controller = new AbortController();
      pendingRequest.current = controller;
      await cleanupActive(false);
      if (!mounted.current || currentOperation !== operation.current) return;
      if (clients === null) {
        setState({ status: "unavailable", error: { kind: "session" } });
        return;
      }
      setState({ status: "starting" });
      const result = await clients.monitoring.createSession(
        attemptId,
        controller.signal,
      );
      if (pendingRequest.current === controller) pendingRequest.current = null;
      if (
        !mounted.current ||
        currentOperation !== operation.current ||
        clients.getSessionGeneration() !== clients.generation
      )
        return;
      if (!result.ok) {
        setState({ status: "unavailable", error: result.error });
        return;
      }
      if (result.value.status !== "ACTIVE") {
        setState({ status: "ended" });
        return;
      }
      if (result.value.attemptId !== attemptId) {
        setState({
          status: "unavailable",
          error: { kind: "invalid-response" },
        });
        return;
      }

      const stream = createActivityEvidenceStream({
        api: clients.monitoring,
        session: result.value,
        getSessionGeneration: clients.getSessionGeneration,
        sessionGeneration: clients.generation,
      });
      const detach = attachBrowserActivityObserver({
        stream,
        attemptId: result.value.attemptId,
      });
      let unsubscribe: () => void = () => undefined;
      const deadlineTimer = setTimeout(
        () => void stream.reconcile(),
        Math.min(
          2_147_000_000,
          Math.max(0, Date.parse(result.value.deadlineAt) - Date.now() + 100),
        ),
      );
      unsubscribe = stream.subscribe(() => {
        const delivery = stream.getSnapshot().delivery;
        if (delivery !== "blocked" && delivery !== "stopped") return;
        if (activeRef.current?.stream !== stream) return;
        activeRef.current = null;
        clearTimeout(deadlineTimer);
        unsubscribe();
        detach();
        if (!mounted.current) return;
        setState(
          delivery === "stopped"
            ? { status: "ended" }
            : {
                status: "unavailable",
                error: { kind: "api", status: 403, code: "AUTHORITY_LOST" },
              },
        );
      });
      if (!mounted.current || currentOperation !== operation.current) {
        clearTimeout(deadlineTimer);
        unsubscribe();
        detach();
        await stream.stop();
        return;
      }
      activeRef.current = {
        stream,
        detach,
        unsubscribe,
        deadlineTimer,
        generation: clients.generation,
      };
      setState({ status: "active", session: result.value, activity: stream });
    },
    [cleanupActive, clients],
  );

  const start = useCallback(
    async (eligibility: MonitoringEligibility) => {
      const decision = evaluateMonitoringEligibility(eligibility);
      if (!decision.eligible) {
        await release(false);
        if (mounted.current)
          setState({ status: "ineligible", reason: decision.reason });
        return;
      }
      await verifyAndStart(decision.attemptId);
    },
    [release, verifyAndStart],
  );

  const observeQuestion = useCallback(
    (
      type:
        | "QUESTION_VIEWED"
        | "QUESTION_LEFT"
        | "ANSWER_SELECTED"
        | "ANSWER_CHANGED"
        | "ANSWER_CLEARED",
      questionNumber: number,
    ) => activeRef.current?.stream.observe({ type, questionNumber }) ?? false,
    [],
  );

  return {
    state,
    start,
    verifyAndStart,
    async stop() {
      const nextOperation = operation.current + 1;
      await release(true);
      if (mounted.current && operation.current === nextOperation)
        setState({ status: "ended" });
    },
    questionViewed: (number) => observeQuestion("QUESTION_VIEWED", number),
    questionLeft: (number) => observeQuestion("QUESTION_LEFT", number),
    answerSelected: (number) => observeQuestion("ANSWER_SELECTED", number),
    answerChanged: (number) => observeQuestion("ANSWER_CHANGED", number),
    answerCleared: (number) => observeQuestion("ANSWER_CLEARED", number),
  };
}
