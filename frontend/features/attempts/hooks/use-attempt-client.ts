"use client";

import { useMemo } from "react";
import { useAuth } from "../../auth/auth-provider";
import { createAttemptApiClient } from "../api/attempt-api-client";

export function useAttemptClient() {
  const { authenticatedRequests, getSessionGeneration } = useAuth();
  const generation = getSessionGeneration();
  return useMemo(
    () =>
      authenticatedRequests === null || generation === null
        ? null
        : createAttemptApiClient({
            authenticatedRequests,
            getSessionGeneration,
          }),
    [authenticatedRequests, getSessionGeneration, generation],
  );
}
