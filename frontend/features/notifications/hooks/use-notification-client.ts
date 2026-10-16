"use client";

import { useMemo } from "react";
import { useAuth } from "../../auth/auth-provider";
import { createNotificationApiClient } from "../api/notification-api-client";

export function useNotificationClient() {
  const { authenticatedRequests, getSessionGeneration } = useAuth();
  const generation = getSessionGeneration();
  return useMemo(
    () =>
      authenticatedRequests === null || generation === null
        ? null
        : createNotificationApiClient({
            authenticatedRequests,
            getSessionGeneration,
          }),
    [authenticatedRequests, getSessionGeneration, generation],
  );
}
