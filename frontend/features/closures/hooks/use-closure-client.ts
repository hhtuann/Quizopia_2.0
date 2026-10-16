"use client";

import { useMemo } from "react";
import { useAuth } from "../../auth/auth-provider";
import { createClosureApiClient } from "../api/closure-api-client";

export function useClosureClient() {
  const { authenticatedRequests, getSessionGeneration } = useAuth();
  const generation = getSessionGeneration();
  return useMemo(
    () =>
      authenticatedRequests === null || generation === null
        ? null
        : createClosureApiClient({
            authenticatedRequests,
            getSessionGeneration,
          }),
    [authenticatedRequests, getSessionGeneration, generation],
  );
}
