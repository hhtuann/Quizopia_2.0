"use client";

import { useMemo } from "react";
import { useAuth } from "../../auth/auth-provider";
import { createPublicationApiClient } from "../api/publication-api-client";

export function usePublicationClient() {
  const { authenticatedRequests, getSessionGeneration } = useAuth();
  const generation = getSessionGeneration();
  return useMemo(
    () =>
      authenticatedRequests === null || generation === null
        ? null
        : createPublicationApiClient({
            authenticatedRequests,
            getSessionGeneration,
          }),
    [authenticatedRequests, getSessionGeneration, generation],
  );
}
