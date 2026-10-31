import type { Answer } from "../api/attempt-contract.generated";
import type { AnswerObservation } from "../../teacher-results/api/teacher-assessment-contract.generated";

type Labels = readonly ("A" | "B" | "C" | "D")[];
function choices(answer: Answer): Labels | null {
  if (answer.type === "SINGLE_CHOICE")
    return answer.optionLabel === null ? [] : [answer.optionLabel];
  if (answer.type === "MULTIPLE_CHOICE") return [...answer.optionLabels].sort();
  return null;
}

/** Session-memory only. Optional delivery cannot delay SAVE/SUBMIT or mutate academic state. */
export class ObservationQueue {
  private queue: AnswerObservation[] = [];
  private controller = new AbortController();
  private stream: string | null = null;
  private sequence = 0;
  private running = false;
  private timer: ReturnType<typeof setTimeout> | undefined;
  constructor(
    private readonly send: (
      event: AnswerObservation,
      signal: AbortSignal,
    ) => Promise<number>,
    private readonly gap: () => void,
    private readonly stopped: () => void = () => {},
  ) {}
  start(stream: string) {
    this.stop();
    this.stream = stream;
    this.controller = new AbortController();
    // Fresh session range within JS safe integers, even when an existing opt-in is resumed.
    this.sequence = crypto.getRandomValues(new Uint32Array(1))[0]! * 1_000_000;
  }
  stop() {
    this.suspend();
    this.stopped();
  }
  suspend() {
    if (this.queue.length > 0) this.gap();
    this.stream = null;
    this.queue = [];
    this.controller.abort();
    clearTimeout(this.timer);
    this.timer = undefined;
  }
  navigation(type: "QUESTION_VIEWED" | "QUESTION_LEFT", question: number) {
    this.emit(type, question);
  }
  answer(question: number, before: Answer, after: Answer) {
    const from = choices(before),
      to = choices(after);
    if (
      from === null ||
      to === null ||
      before.type !== after.type ||
      JSON.stringify(from) === JSON.stringify(to)
    )
      return;
    this.emit(
      to.length === 0
        ? "ANSWER_CLEARED"
        : from.length === 0
          ? "ANSWER_SELECTED"
          : "ANSWER_CHANGED",
      question,
      { fromOptionLabels: from, toOptionLabels: to },
    );
  }
  private emit(
    type: AnswerObservation["type"],
    question: number,
    transition?: AnswerObservation["transition"],
  ) {
    if (this.stream === null) return;
    if (this.queue.length >= 100 || !Number.isSafeInteger(this.sequence + 1)) {
      this.gap();
      return;
    }
    const event: AnswerObservation = {
      schemaVersion: 1,
      eventId: crypto.randomUUID(),
      streamId: this.stream,
      sequence: this.sequence++,
      type,
      questionNumber: question,
      observedAt: new Date().toISOString(),
      ...(transition === undefined ? {} : { transition }),
    };
    if (new TextEncoder().encode(JSON.stringify(event)).length > 4096) {
      this.gap();
      return;
    }
    this.queue.push(event);
    void this.drain();
  }
  private async drain() {
    if (this.running || this.stream === null) return;
    this.running = true;
    const controller = this.controller;
    try {
      while (this.queue.length > 0 && !controller.signal.aborted) {
        const event = this.queue[0]!;
        let status = 503;
        for (let retry = 0; retry <= 3 && !controller.signal.aborted; retry++) {
          try {
            status = await this.send(event, controller.signal);
          } catch {
            status = 503;
          }
          if (status !== 429 && status < 500) break;
          if (retry < 3)
            await new Promise<void>((resolve) => {
              const finish = () => {
                clearTimeout(this.timer);
                controller.signal.removeEventListener("abort", finish);
                resolve();
              };
              this.timer = setTimeout(finish, 1000 * 2 ** retry);
              controller.signal.addEventListener("abort", finish, {
                once: true,
              });
            });
        }
        if (controller.signal.aborted) return;
        this.queue.shift();
        if (status !== 200) this.gap();
        if ([401, 403, 404, 409].includes(status)) {
          this.stop();
          return;
        }
      }
    } finally {
      this.running = false;
      if (this.stream !== null && this.queue.length > 0) void this.drain();
    }
  }
}
