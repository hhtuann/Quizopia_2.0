"use client";

import {
  type KeyboardEvent,
  type RefObject,
  useEffect,
  useRef,
  useState,
} from "react";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { useI18n } from "../../../lib/i18n/provider";
import {
  newDeletionIdempotencyKey,
  type QuizDeletionAccepted,
  type QuizDeletionApiClient,
  type QuizDeletionFailure,
} from "../api/quiz-deletion-client";

/**
 * FB-015 destructive confirmation dialog (ADR-018 UX).
 *
 * Mount the component only while the dialog is open (parents render it
 * conditionally). Unresolved intents retain their Idempotency-Key in tab storage.
 * Retries of the same intent reuse
 * the key verbatim, so an ambiguous network failure can never create a second
 * deletion operation. The confirm action unlocks only when the teacher types
 * the EXACT current quiz title (trimmed, case-sensitive) as known from server
 * data — the backend re-checks authoritatively.
 */
export interface QuizDeletionDialogProps {
  readonly client: QuizDeletionApiClient;
  readonly quizId: string;
  /** Authoritative current title from server data; blank when untitled. */
  readonly authoritativeTitle: string | null;
  readonly onClose: () => void;
  readonly onAccepted: (accepted: QuizDeletionAccepted) => void;
  readonly triggerRef?: RefObject<HTMLElement | null>;
}

function focusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(
    container.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ),
  ).filter((element) => !element.hasAttribute("hidden"));
}

function deletionFailureMessage(
  failure: QuizDeletionFailure,
  localize: (
    message: string,
    values?: Record<string, string | number>,
  ) => string,
): string {
  if (failure.kind === "api-error") {
    const { code, status } = failure.error;
    if (code === "DELETION_CONFLICT") {
      return localize(
        "A deletion was already requested for this quiz. Reload to see its current status.",
      );
    }
    if (code === "QUIZ_DELETION_PENDING") {
      return localize("This quiz is already being deleted.");
    }
    if (code === "TITLE_CONFIRMATION_REQUIRED") {
      return localize(
        "The typed title does not match the current quiz title. Reload the quiz and try again.",
      );
    }
    if (status === 404) {
      return localize("This quiz no longer exists. Reload your quiz library.");
    }
    // Server-authored diagnostics stay verbatim.
    return failure.error.message;
  }
  if (failure.kind === "transport-error") {
    return localize("Quizopia could not connect. Try again.");
  }
  if (
    failure.kind === "authentication-failure" ||
    failure.kind === "session-changed"
  ) {
    return localize("Your session has ended. Please sign in again.");
  }
  if (failure.kind === "invalid-input") {
    return localize(
      "The title does not match. Type it exactly, including capitalization.",
    );
  }
  return localize("Quizopia got an unexpected response ({status}).", {
    status: failure.status,
  });
}

export function QuizDeletionDialog({
  client,
  quizId,
  authoritativeTitle,
  onClose,
  onAccepted,
  triggerRef,
}: QuizDeletionDialogProps) {
  const { localize, t } = useI18n();
  const dialogRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  // Persist the identity before sending: an uncertain outcome survives reopen/reload.
  const intentStorageKey = `quizopia.quiz-deletion.intent.${quizId}`;
  const [isRecoveredIntent] = useState(() => {
    try {
      return sessionStorage.getItem(intentStorageKey) !== null;
    } catch {
      return false;
    }
  });
  const [idempotencyKey] = useState(() => {
    try {
      return (
        sessionStorage.getItem(intentStorageKey) ?? newDeletionIdempotencyKey()
      );
    } catch {
      return newDeletionIdempotencyKey();
    }
  });
  const [confirmation, setConfirmation] = useState("");
  const [untitledConfirmed, setUntitledConfirmed] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [failure, setFailure] = useState<QuizDeletionFailure | null>(null);

  const canonicalTitle = authoritativeTitle?.trim() ?? "";
  const hasTitle = canonicalTitle !== "";
  const typed = confirmation.trim();
  const matchesExactly = hasTitle
    ? typed === canonicalTitle
    : untitledConfirmed;
  const showMismatch = !matchesExactly && typed !== "" && !isSubmitting;

  useEffect(() => {
    // Capture the trigger now; the ref may be null by cleanup time.
    const trigger = triggerRef?.current ?? null;
    const frame = window.requestAnimationFrame(() => inputRef.current?.focus());
    return () => {
      window.cancelAnimationFrame(frame);
      trigger?.focus();
    };
  }, [triggerRef]);

  async function submit() {
    if (!matchesExactly || isSubmitting) {
      return;
    }
    setIsSubmitting(true);
    setFailure(null);
    try {
      sessionStorage.setItem(intentStorageKey, idempotencyKey);
    } catch {
      // Same mounted intent still retains its key if storage is unavailable.
    }
    // Reconcile first on retries; never blindly create another operation.
    if (failure !== null || isRecoveredIntent) {
      const latest = await client.getLatestDeletionStatus(quizId);
      if (latest.ok) {
        try {
          sessionStorage.removeItem(intentStorageKey);
        } catch {
          /* Optional storage. */
        }
        setIsSubmitting(false);
        onAccepted({
          operationId: latest.value.operationId,
          status: latest.value.status as QuizDeletionAccepted["status"],
        });
        return;
      }
      if (!(
        latest.error.kind === "api-error" && latest.error.error.status === 404
      )) {
        setIsSubmitting(false);
        setFailure(latest.error);
        return;
      }
    }
    const result = await client.requestDeletion(quizId, {
      confirmTitle: hasTitle ? typed : "",
      idempotencyKey,
    });
    setIsSubmitting(false);
    if (result.ok) {
      try {
        sessionStorage.removeItem(intentStorageKey);
      } catch {
        /* Optional storage. */
      }
      onAccepted(result.value);
      return;
    }
    setFailure(result.error);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      if (!isSubmitting) {
        event.preventDefault();
        onClose();
      }
      return;
    }
    if (event.key !== "Tab" || dialogRef.current === null) {
      return;
    }
    const focusable = focusableElements(dialogRef.current);
    const first = focusable[0];
    const last = focusable.at(-1);
    if (first === undefined || last === undefined) {
      return;
    }
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return (
    <div
      aria-labelledby="quiz-deletion-dialog-title"
      aria-modal="true"
      className="fixed inset-0 z-50 flex items-center justify-center bg-foreground/35 p-4"
      onClick={(event) => {
        if (event.currentTarget === event.target && !isSubmitting) {
          onClose();
        }
      }}
      onKeyDown={handleKeyDown}
      role="dialog"
    >
      <Surface
        className="m-auto w-full max-w-xl p-5 shadow-card sm:p-6"
        ref={dialogRef}
      >
        <h2
          className="text-xl font-semibold text-foreground"
          id="quiz-deletion-dialog-title"
        >
          {t("Delete this quiz permanently?")}
        </h2>
        <div className="mt-3 space-y-3 text-sm leading-6 text-foreground-secondary">
          <p>
            {t(
              "This permanently deletes the quiz, its draft and every published version. Linked assessments, answers, grades and results are permanently deleted when they are eligible; some records can be kept longer only when the law requires it.",
            )}
          </p>
          <p className="font-semibold text-danger">
            {t("Students immediately lose access once revocation completes.")}
          </p>
        </div>

        {hasTitle ? (
          <div className="mt-5">
            <p className="text-sm font-semibold text-foreground-secondary">
              {t("Current title")}
            </p>
            <p className="mt-1 break-words rounded-lg border border-border bg-surface-muted px-3 py-2 font-semibold text-foreground">
              {canonicalTitle}
            </p>
            <label
              className="mt-4 block text-sm font-semibold text-foreground-secondary"
              htmlFor="quiz-deletion-confirmation"
            >
              {t("Type the exact title to confirm")}
            </label>
            <input
              aria-invalid={showMismatch || undefined}
              autoComplete="off"
              className="mt-2 min-h-11 w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-base text-foreground shadow-sm outline-none transition-colors hover:border-primary focus-visible:border-primary focus-visible:ring-2 focus-visible:ring-focus motion-reduce:transition-none"
              disabled={isSubmitting}
              id="quiz-deletion-confirmation"
              onChange={(event) => setConfirmation(event.target.value)}
              placeholder={canonicalTitle}
              ref={inputRef}
              value={confirmation}
            />
            {showMismatch ? (
              <p className="mt-2 text-sm text-danger" role="alert">
                {t(
                  "The title does not match. Type it exactly, including capitalization.",
                )}
              </p>
            ) : null}
          </div>
        ) : (
          <label className="mt-5 flex items-start gap-3 text-sm font-semibold text-danger">
            <input
              checked={untitledConfirmed}
              className="mt-1 size-5 shrink-0"
              disabled={isSubmitting}
              onChange={(event) => setUntitledConfirmed(event.target.checked)}
              ref={inputRef}
              type="checkbox"
            />
            {t(
              "I understand that permanently deleting this untitled quiz is irreversible.",
            )}
          </label>
        )}

        {failure !== null ? (
          <Alert
            className="mt-4"
            title={t("Deletion could not be requested")}
            variant="danger"
          >
            {deletionFailureMessage(failure, localize)}
          </Alert>
        ) : null}

        <div className="mt-6 flex flex-wrap justify-end gap-3">
          <Button disabled={isSubmitting} onClick={onClose} variant="secondary">
            {t("Cancel")}
          </Button>
          <Button
            disabled={!matchesExactly}
            isLoading={isSubmitting}
            loadingLabel={t("Requesting deletion")}
            onClick={() => void submit()}
            variant="danger"
          >
            {t("Delete permanently")}
          </Button>
        </div>
      </Surface>
    </div>
  );
}
