"use client";

import { useMemo } from "react";
import { useAuth } from "../../auth/auth-provider";
import { createTeacherResultsApiClient } from "../api/teacher-results-api-client";

export function useTeacherResultsClient() {
  const { authenticatedRequests, getSessionGeneration } = useAuth();
  const generation = getSessionGeneration();
  return useMemo(
    () =>
      authenticatedRequests === null || generation === null
        ? null
        : createTeacherResultsApiClient({
            authenticatedRequests,
            getSessionGeneration,
          }),
    [authenticatedRequests, getSessionGeneration, generation],
  );
}
