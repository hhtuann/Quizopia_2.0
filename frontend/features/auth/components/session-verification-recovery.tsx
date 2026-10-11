"use client";

import { useState } from "react";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { useAuth } from "../auth-provider";

export function SessionVerificationRecovery({
  onVerified,
}: {
  readonly onVerified?: () => void;
}) {
  const { retryVerification } = useAuth();
  const [isRetrying, setIsRetrying] = useState(false);

  async function retry() {
    setIsRetrying(true);
    try {
      const result = await retryVerification();
      if (result.ok) onVerified?.();
    } finally {
      setIsRetrying(false);
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
