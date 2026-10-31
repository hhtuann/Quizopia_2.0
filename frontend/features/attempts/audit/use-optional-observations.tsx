"use client";

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useAuth } from "../../auth/auth-provider";
import { useI18n } from "../../../lib/i18n/provider";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { observationClient } from "./observation-client";
import { ObservationQueue } from "./observation-queue";

export function useOptionalObservations(
  publication: string,
  attempt: string,
  active: boolean,
  question: number,
) {
  const { authenticatedRequests, getSessionGeneration } = useAuth();
  const generation = getSessionGeneration();
  const { t } = useI18n();
  const [available, setAvailable] = useState(false);
  const [enabled, setEnabled] = useState(false);
  const [busy, setBusy] = useState(false);
  const [partial, setPartial] = useState(false);
  const lifetime = useRef<AbortController | null>(null);
  const activeRef = useRef(active);
  const consentedStream = useRef<string | null>(null);
  const currentQuestion = useRef(question);
  const client = useMemo(
    () =>
      authenticatedRequests === null || generation === null
        ? null
        : observationClient(
            authenticatedRequests,
            getSessionGeneration,
            publication,
            attempt,
          ),
    [
      authenticatedRequests,
      getSessionGeneration,
      generation,
      publication,
      attempt,
    ],
  );
  const queue = useMemo(
    () =>
      new ObservationQueue(
        (event, signal) =>
          client === null ? Promise.resolve(503) : client.send(event, signal),
        () => setPartial(true),
        () => setEnabled(false),
      ),
    [client],
  );
  useLayoutEffect(() => {
    activeRef.current = active;
    currentQuestion.current = question;
    if (!active) queue.suspend();
  }, [active, question, queue]);
  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    if (client !== null)
      void client.choice(undefined, controller.signal).then((choice) => {
        if (!controller.signal.aborted)
          setAvailable(choice?.available === true);
      });
    return () => {
      controller.abort();
      queue.stop();
    };
  }, [client, queue]);
  useEffect(() => {
    // Queue stop or explicit withdrawal both disable collection; forget the
    // consented stream only after state settles so resume revalidates it.
    if (!enabled) consentedStream.current = null;
  }, [enabled]);
  useEffect(() => {
    const wanted = consentedStream.current;
    if (!active || client === null || wanted === null) return;
    const controller = new AbortController();
    void client.choice(undefined, controller.signal).then((choice) => {
      if (controller.signal.aborted || consentedStream.current !== wanted)
        return;
      if (choice?.available && choice.enabled && choice.streamId === wanted) {
        queue.start(wanted);
        consentedStream.current = wanted;
        setEnabled(true);
        queue.navigation("QUESTION_VIEWED", currentQuestion.current);
      } else {
        queue.stop();
        setPartial(true);
      }
    });
    return () => controller.abort();
  }, [active, client, queue]);
  useEffect(() => {
    if (!active || !enabled) return;
    queue.navigation("QUESTION_VIEWED", question);
    return () => queue.navigation("QUESTION_LEFT", question);
  }, [active, enabled, question, queue]);
  async function choose(next: boolean) {
    if (client === null || lifetime.current === null || busy) return;
    if (!next) {
      queue.stop();
      setEnabled(false);
    }
    setBusy(true);
    const controller = lifetime.current;
    const result = await client.choice(next, controller.signal);
    if (controller.signal.aborted) return;
    setBusy(false);
    if (
      result === null ||
      result.enabled !== next ||
      (next && result.streamId === null)
    ) {
      setPartial(true);
      return;
    }
    if (next && activeRef.current) {
      queue.start(result.streamId!);
      consentedStream.current = result.streamId;
      setEnabled(true);
    }
  }
  const content =
    available && active ? (
      <Surface
        className="space-y-3 p-4"
        aria-label={t("Optional interaction history")}
      >
        <h2 className="font-heading text-base font-semibold">
          {t("Optional interaction history")}
        </h2>
        <p className="text-sm text-foreground-secondary">
          {t(
            "Fictional demo accounts only. With your permission, record question navigation and A–D choice changes, including unsaved changes. No numeric input, keystrokes, camera or screen content. History may be incomplete and is never proof of misconduct.",
          )}
        </p>
        <p className="text-sm text-foreground-secondary">
          {t(
            "Only the owning Teacher can read submitted, graded work. Optional events expire after 30 days, system audit after 90 days and Teacher access receipts after 180 days; reviewed holds may delay deletion. Academic grades follow their separate policy. Contact the development team for this fictional demo. You can decline or stop without affecting your assessment.",
          )}
        </p>
        <div className="flex flex-wrap gap-3">
          {enabled ? (
            <Button
              size="touch"
              variant="secondary"
              disabled={busy}
              onClick={() => void choose(false)}
            >
              {t("Stop optional history")}
            </Button>
          ) : (
            <>
              <Button
                size="touch"
                variant="secondary"
                disabled={busy}
                onClick={() => void choose(true)}
              >
                {t("Enable optional history")}
              </Button>
              <Button
                size="touch"
                variant="secondary"
                disabled={busy}
                onClick={() => void choose(false)}
              >
                {t("Continue without history")}
              </Button>
            </>
          )}
        </div>
        <p role="status" className="text-sm">
          {enabled
            ? t("Optional history is on for this session.")
            : t("Optional history is off.")}
        </p>
        {partial ? (
          <p className="text-sm">
            {t(
              "History is incomplete or unavailable. You can keep answering and submitting.",
            )}
          </p>
        ) : null}
      </Surface>
    ) : null;
  return { queue, content };
}
