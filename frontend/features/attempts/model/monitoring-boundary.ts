import type { AttemptView } from "../api/attempt-contract.generated";

/**
 * Read-only composition boundary for a future authorized monitoring adapter.
 * Consumers may observe lifecycle and navigation only; they cannot read answers
 * or become an authoritative mutation path.
 */
export interface AttemptMonitoringObserver {
  readonly enabled: boolean;
  onAttemptLifecycle?(event: {
    readonly attemptId: string;
    readonly state: AttemptView["state"];
  }): void;
  onQuestionSelection?(event: {
    readonly attemptId: string;
    readonly questionNumber: number;
  }): void;
}

export const NO_ATTEMPT_MONITORING: AttemptMonitoringObserver = Object.freeze({
  enabled: false,
});
