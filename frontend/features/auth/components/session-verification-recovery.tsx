"use client";

import { useEffect, useRef, useState } from "react";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { useAuth } from "../auth-provider";
import { useI18n } from "../../../lib/i18n/provider";

export function SessionVerificationRecovery() {
  const { t } = useI18n();
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
        title={t("Account verification is temporarily unavailable")}
        variant="warning"
      >
        {t(
          "We could not verify your account yet. Access remains unavailable until verification succeeds. Try again when the service is available, or sign in with another account.",
        )}
      </Alert>
      <p
        role="status"
        aria-label={t("Verification progress")}
        aria-live="polite"
        className="text-sm text-foreground-secondary"
      >
        {isRetrying
          ? t("Checking your account. Please wait.")
          : failedAttempts > 0
            ? t(
                "Verification attempt {count} could not be completed. Access remains unavailable. You can retry or sign in again.",
                { count: failedAttempts },
              )
            : null}
      </p>
      <Button
        aria-label={isRetrying ? t("Verifying account") : undefined}
        isLoading={isRetrying}
        loadingLabel={t("Verifying account")}
        onClick={() => void retry()}
        type="button"
      >
        {t("Retry account verification")}
      </Button>
    </div>
  );
}
