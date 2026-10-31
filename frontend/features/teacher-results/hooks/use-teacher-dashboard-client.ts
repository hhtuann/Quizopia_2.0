"use client";

import { useMemo } from "react";
import { useAuth } from "../../auth/auth-provider";
import { createTeacherDashboardApiClient } from "../api/teacher-dashboard-api-client";

export function useTeacherDashboardClient() {
  const { authenticatedRequests, getSessionGeneration } = useAuth();
  const generation = getSessionGeneration();
  return useMemo(
    () =>
      authenticatedRequests === null || generation === null
        ? null
        : createTeacherDashboardApiClient({
            authenticatedRequests,
            getSessionGeneration,
          }),
    [authenticatedRequests, getSessionGeneration, generation],
  );
}
