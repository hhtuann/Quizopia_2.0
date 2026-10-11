"use client";

import { useEffect, useRef, useState } from "react";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { useAuth } from "../auth-provider";

export function SessionVerificationRecovery() {
  const { retryVerification, session } = useAuth();
  const [failedAttempts, setFailedAttempts] = useState(0);
  const lifetime = useRef(0);
  useEffect(
    () => () => {
      lifetime.current += 1;
    },
    [],
  );
  const isRetrying =
    session.status === "verification-unavailable" &&
    session.isVerifying === true;

  async function retry() {
    const startedIn = lifetime.current;
    const result = await retryVerification();
    if (startedIn !== lifetime.current) return;
    if (!result.ok) {
      setFailedAttempts((count) => count + 1);
    }
  }

  return (
    <div className="space-y-4">
      <Alert
        role="alert"
        title="Account verification is temporarily unavailable"
        variant="warning"
      >
        We could not verify your account yet. Access remains unavailable until
        verification succeeds. Try again when the service is available, or sign
        in with another account.
      </Alert>
      <p
        role="status"
        aria-label="Verification progress"
        aria-live="polite"
        className="text-sm text-foreground-secondary"
      >
        {isRetrying
          ? "Checking your account. Please wait."
          : failedAttempts > 0
            ? `Verification attempt ${failedAttempts} could not be completed. Access remains unavailable. You can retry or sign in again.`
            : null}
      </p>
      <Button
        isLoading={isRetrying}
        loadingLabel="Verifying account"
        onClick={() => void retry()}
        type="button"
      >
        Retry account verification
      </Button>
    </div>
  );
}
