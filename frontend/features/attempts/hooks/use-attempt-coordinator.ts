"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  Answer,
  AttemptView,
  SaveRequest,
} from "../api/attempt-contract.generated";
import {
  attemptFailureMessage,
  isUncertainAttemptMutation,
  type AttemptApiClient,
} from "../api/attempt-api-client";
import {
  isValidNumericToken,
  newIdempotencyKey,
  normalizeNumericInput,
  sameAnswer,
} from "../model/attempt";
import { useUnsavedNavigationGuard } from "./use-unsaved-navigation-guard";

export type AnswerSavePhase =
  "saved" | "draft" | "saving" | "failed" | "conflict" | "uncertain";

export interface AnswerSaveStatus {
  readonly phase: AnswerSavePhase;
  readonly message?: string;
}

interface SaveIntent {
  readonly answer: Answer;
  readonly editVersion: number;
  readonly key: string;
  readonly questionNumber: number;
  readonly request: SaveRequest;
}

function answerRecord(attempt: AttemptView): Record<number, Answer> {
  return Object.fromEntries(
    attempt.answers.map((entry) => [entry.questionNumber, entry.answer]),
  );
}

function initialStatuses(
  attempt: AttemptView,
): Record<number, AnswerSaveStatus> {
  return Object.fromEntries(
    attempt.questions.map((question) => [question.number, { phase: "saved" }]),
  );
}

function canonicalAnswer(answer: Answer): Answer | null {
  if (answer.type !== "NUMERIC_FILL") return answer;
  const value = normalizeNumericInput(answer.value ?? "");
  if (value !== null && !isValidNumericToken(value)) return null;
  return { type: "NUMERIC_FILL", value };
}

export function useAttemptCoordinator(options: {
  readonly attempt: AttemptView;
  readonly client: AttemptApiClient;
}) {
  const { client } = options;
  const [attempt, setAttempt] = useState(options.attempt);
  const [drafts, setDrafts] = useState<Record<number, Answer>>(() =>
    answerRecord(options.attempt),
  );
  const [statuses, setStatuses] = useState<Record<number, AnswerSaveStatus>>(
    () => initialStatuses(options.attempt),
  );
  const attemptRef = useRef(attempt);
  const draftsRef = useRef(drafts);
  const statusesRef = useRef(statuses);
  const remoteAnswers = useRef(answerRecord(options.attempt));
  const editVersions = useRef<Record<number, number>>({});
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());
  const uncertain = useRef(new Map<number, SaveIntent>());
  const queue = useRef<Promise<void>>(Promise.resolve());
  const disposed = useRef(false);
  const mutationsPaused = useRef(false);

  const updateAttempt = useCallback(
    (next: AttemptView, preserveDrafts = false) => {
      if (BigInt(next.revision) < BigInt(attemptRef.current.revision)) return;
      const previousRemote = remoteAnswers.current;
      const nextRemote = answerRecord(next);
      const mergedDrafts = Object.fromEntries(
        next.questions.map((question) => {
          const questionNumber = question.number;
          const local = draftsRef.current[questionNumber];
          const previous = previousRemote[questionNumber];
          const authoritative = nextRemote[questionNumber];
          const canAdoptAuthoritative =
            !preserveDrafts &&
            statusesRef.current[questionNumber]?.phase === "saved" &&
            local !== undefined &&
            previous !== undefined &&
            authoritative !== undefined &&
            sameAnswer(local, previous);
          return [
            questionNumber,
            canAdoptAuthoritative ? authoritative : (local ?? authoritative),
          ];
        }),
      );
      attemptRef.current = next;
      draftsRef.current = mergedDrafts;
      remoteAnswers.current = nextRemote;
      if (preserveDrafts) {
        const reconciledStatuses = Object.fromEntries(
          next.questions.map((question) => {
            const questionNumber = question.number;
            const local = mergedDrafts[questionNumber];
            const authoritative = nextRemote[questionNumber];
            return [
              questionNumber,
              local !== undefined &&
              authoritative !== undefined &&
              sameAnswer(local, authoritative)
                ? { phase: "saved" as const }
                : {
                    phase: "conflict" as const,
                    message:
                      "Authoritative state changed. Your local answer is preserved; save it deliberately before retrying the terminal action.",
                  },
            ];
          }),
        );
        statusesRef.current = reconciledStatuses;
        setStatuses(reconciledStatuses);
      }
      setAttempt(next);
      setDrafts(mergedDrafts);
    },
    [],
  );

  const updateStatuses = useCallback(
    (
      update: (
        current: Record<number, AnswerSaveStatus>,
      ) => Record<number, AnswerSaveStatus>,
    ) => {
      const next = update(statusesRef.current);
      statusesRef.current = next;
      setStatuses(next);
    },
    [],
  );

  const setQuestionStatus = useCallback(
    (questionNumber: number, status: AnswerSaveStatus) => {
      updateStatuses((current) => ({ ...current, [questionNumber]: status }));
    },
    [updateStatuses],
  );

  const setMutationPaused = useCallback(
    (paused: boolean) => {
      mutationsPaused.current = paused;
      if (!paused) return;
      const pendingQuestions = [...timers.current.keys()];
      for (const timer of timers.current.values()) clearTimeout(timer);
      timers.current.clear();
      for (const questionNumber of pendingQuestions)
        setQuestionStatus(questionNumber, {
          phase: "draft",
          message:
            "Autosave is paused until authoritative Attempt status is refreshed. Your draft remains in memory.",
        });
    },
    [setQuestionStatus],
  );

  const reconcileConflict = useCallback(
    async (intent: SaveIntent) => {
      const fresh = await client.getAttempt(attemptRef.current.attemptId);
      if (!fresh.ok) {
        setQuestionStatus(intent.questionNumber, {
          phase: "conflict",
          message: `The server revision changed, and refresh failed. ${attemptFailureMessage(fresh.error)}`,
        });
        return;
      }
      updateAttempt(fresh.value);
      setQuestionStatus(intent.questionNumber, {
        phase: "conflict",
        message:
          fresh.value.state === "ACTIVE"
            ? "Server state was refreshed. Your local edit is preserved; choose Save this draft to apply it against the new revision."
            : "The attempt became terminal. Your local edit was preserved locally but cannot be saved.",
      });
    },
    [client, setQuestionStatus, updateAttempt],
  );

  const performSave = useCallback(
    async (intent: SaveIntent) => {
      if (disposed.current || attemptRef.current.state !== "ACTIVE") return;
      if (mutationsPaused.current) {
        setQuestionStatus(
          intent.questionNumber,
          uncertain.current.get(intent.questionNumber) === intent
            ? {
                phase: "uncertain",
                message:
                  "Exact retry is paused until authoritative Attempt status is refreshed.",
              }
            : {
                phase: "draft",
                message:
                  "Saving is paused until authoritative Attempt status is refreshed.",
              },
        );
        return;
      }
      setQuestionStatus(intent.questionNumber, { phase: "saving" });
      const result = await client.save(
        attemptRef.current.attemptId,
        intent.questionNumber,
        intent.request,
        intent.key,
      );
      if (disposed.current) return;
      if (result.ok) {
        uncertain.current.delete(intent.questionNumber);
        updateAttempt({
          ...attemptRef.current,
          revision: result.value.revision,
          answers: attemptRef.current.answers.map((entry) =>
            entry.questionNumber === intent.questionNumber
              ? { ...entry, answer: intent.answer }
              : entry,
          ),
        });
        const currentVersion = editVersions.current[intent.questionNumber] ?? 0;
        const currentDraft = draftsRef.current[intent.questionNumber];
        if (
          currentVersion === intent.editVersion &&
          currentDraft !== undefined &&
          sameAnswer(currentDraft, intent.answer)
        ) {
          setQuestionStatus(intent.questionNumber, { phase: "saved" });
        } else {
          setQuestionStatus(intent.questionNumber, { phase: "draft" });
        }
        return;
      }
      if (isUncertainAttemptMutation(result.error)) {
        uncertain.current.set(intent.questionNumber, intent);
        setQuestionStatus(intent.questionNumber, {
          phase: "uncertain",
          message:
            "The save outcome is unknown. Retry the exact save or check authoritative state before sending another version.",
        });
        return;
      }
      uncertain.current.delete(intent.questionNumber);
      if (
        result.error.kind === "api" &&
        result.error.code === "REVISION_CONFLICT"
      ) {
        await reconcileConflict(intent);
        return;
      }
      if (
        result.error.kind === "api" &&
        [
          "ATTEMPT_TERMINAL",
          "ATTEMPT_DEADLINE_REACHED",
          "CLOSURE_CUTOFF_REACHED",
        ].includes(result.error.code)
      ) {
        const fresh = await client.getAttempt(attemptRef.current.attemptId);
        if (fresh.ok) updateAttempt(fresh.value);
      }
      setQuestionStatus(intent.questionNumber, {
        phase: "failed",
        message: attemptFailureMessage(result.error),
      });
    },
    [client, reconcileConflict, setQuestionStatus, updateAttempt],
  );

  const enqueueIntent = useCallback(
    (intent: SaveIntent) => {
      queue.current = queue.current.then(() => performSave(intent));
      return queue.current;
    },
    [performSave],
  );

  const createIntent = useCallback(
    (questionNumber: number): SaveIntent | null => {
      const draft = draftsRef.current[questionNumber];
      if (draft === undefined) return null;
      const answer = canonicalAnswer(draft);
      if (answer === null) {
        setQuestionStatus(questionNumber, {
          phase: "failed",
          message:
            "Enter exactly four numeric characters, such as 2.50, 1234, -2.5, or -.25; blank means unanswered.",
        });
        return null;
      }
      return {
        answer,
        editVersion: editVersions.current[questionNumber] ?? 0,
        key: newIdempotencyKey(),
        questionNumber,
        request: {
          expectedRevision: attemptRef.current.revision,
          answer,
        },
      };
    },
    [setQuestionStatus],
  );

  const saveCurrentDraft = useCallback(
    (questionNumber: number) => {
      const timer = timers.current.get(questionNumber);
      if (timer !== undefined) {
        clearTimeout(timer);
        timers.current.delete(questionNumber);
      }
      queue.current = queue.current.then(async () => {
        if (uncertain.current.size > 0) {
          if (!uncertain.current.has(questionNumber))
            setQuestionStatus(questionNumber, {
              phase: "draft",
              message:
                "Resolve the uncertain save before another answer can be sent.",
            });
          return;
        }
        const intent = createIntent(questionNumber);
        if (intent !== null) await performSave(intent);
      });
      return queue.current;
    },
    [createIntent, performSave, setQuestionStatus],
  );

  const changeAnswer = useCallback(
    (questionNumber: number, answer: Answer) => {
      if (mutationsPaused.current || attemptRef.current.state !== "ACTIVE")
        return;
      editVersions.current[questionNumber] =
        (editVersions.current[questionNumber] ?? 0) + 1;
      draftsRef.current = { ...draftsRef.current, [questionNumber]: answer };
      setDrafts(draftsRef.current);
      setQuestionStatus(
        questionNumber,
        uncertain.current.has(questionNumber)
          ? {
              phase: "uncertain",
              message:
                "Your newer draft is preserved. Resolve the prior unknown save before sending this version.",
            }
          : { phase: "draft" },
      );
      const existing = timers.current.get(questionNumber);
      if (existing !== undefined) clearTimeout(existing);
      timers.current.set(
        questionNumber,
        setTimeout(() => {
          timers.current.delete(questionNumber);
          void saveCurrentDraft(questionNumber);
        }, 650),
      );
    },
    [saveCurrentDraft, setQuestionStatus],
  );

  const retryUncertain = useCallback(
    (questionNumber: number) => {
      const intent = uncertain.current.get(questionNumber);
      return intent === undefined ? Promise.resolve() : enqueueIntent(intent);
    },
    [enqueueIntent],
  );

  const checkAuthoritativeState = useCallback(
    async (questionNumber: number) => {
      const intent = uncertain.current.get(questionNumber);
      if (intent === undefined) return;
      const result = await client.getAttempt(attemptRef.current.attemptId);
      if (!result.ok) {
        setQuestionStatus(questionNumber, {
          phase: "uncertain",
          message: attemptFailureMessage(result.error),
        });
        return;
      }
      updateAttempt(result.value);
      const serverAnswer = remoteAnswers.current[questionNumber];
      if (
        serverAnswer !== undefined &&
        result.value.revision !== intent.request.expectedRevision &&
        sameAnswer(serverAnswer, intent.answer)
      ) {
        uncertain.current.delete(questionNumber);
        const currentDraft = draftsRef.current[questionNumber];
        setQuestionStatus(questionNumber, {
          phase:
            currentDraft !== undefined &&
            sameAnswer(currentDraft, intent.answer)
              ? "saved"
              : "draft",
        });
      } else {
        uncertain.current.delete(questionNumber);
        setQuestionStatus(questionNumber, {
          phase: "conflict",
          message:
            "Authoritative state does not confirm this save. Your local draft remains available; choose Save this draft to create a new operation.",
        });
      }
    },
    [client, setQuestionStatus, updateAttempt],
  );

  const flushOrdinarySaves = useCallback(async () => {
    if (mutationsPaused.current || uncertain.current.size > 0) return null;
    const pending: Promise<void>[] = [];
    for (const [questionNumber, timer] of timers.current) {
      clearTimeout(timer);
      timers.current.delete(questionNumber);
      pending.push(saveCurrentDraft(questionNumber));
    }
    await Promise.all(pending);
    await queue.current;
    return Object.values(statusesRef.current).every(
      (status) => status.phase === "saved",
    )
      ? {
          attempt: attemptRef.current,
          answers: { ...draftsRef.current },
        }
      : null;
  }, [saveCurrentDraft]);

  const refresh = useCallback(async () => {
    const result = await client.getAttempt(attemptRef.current.attemptId);
    if (result.ok) updateAttempt(result.value);
    return result;
  }, [client, updateAttempt]);

  const refreshPreservingDrafts = useCallback(async () => {
    const result = await client.getAttempt(attemptRef.current.attemptId);
    if (result.ok) updateAttempt(result.value, true);
    return result;
  }, [client, updateAttempt]);

  const hasUnsavedWork = useMemo(
    () =>
      attempt.state === "ACTIVE" &&
      Object.values(statuses).some((status) => status.phase !== "saved"),
    [attempt.state, statuses],
  );
  useUnsavedNavigationGuard(
    hasUnsavedWork,
    "This Attempt has unsaved or unresolved answers. Leave this page anyway?",
  );

  useEffect(() => {
    disposed.current = false;
    const scheduledTimers = timers.current;
    return () => {
      disposed.current = true;
      for (const timer of scheduledTimers.values()) clearTimeout(timer);
      scheduledTimers.clear();
    };
  }, []);

  return {
    attempt,
    answers: drafts,
    statuses,
    hasUnsavedWork,
    changeAnswer,
    checkAuthoritativeState,
    flushOrdinarySaves,
    refresh,
    refreshPreservingDrafts,
    setMutationPaused,
    retryFailed: saveCurrentDraft,
    retryUncertain,
    saveConflict: saveCurrentDraft,
  };
}
