"use client";

import { useMemo } from "react";
import { useAuth } from "../../auth/auth-provider";
import {
  createMediaApiClient,
  type MediaApiClient,
} from "../api/media-api-client";
import {
  createMonitoringApiClient,
  type MonitoringApiClient,
} from "../api/monitoring-api-client";

export interface ProctoringClients {
  readonly media: MediaApiClient;
  readonly monitoring: MonitoringApiClient;
  readonly generation: number;
  readonly getSessionGeneration: () => number | null;
}

export function useProctoringClients(): ProctoringClients | null {
  const { authenticatedRequests, getSessionGeneration, session } = useAuth();
  const generation = getSessionGeneration();

  return useMemo(() => {
    if (
      authenticatedRequests === null ||
      generation === null ||
      (session.status !== "authenticated" && session.status !== "refreshing")
    ) {
      return null;
    }
    return {
      media: createMediaApiClient({
        authenticatedRequests,
        getSessionGeneration,
      }),
      monitoring: createMonitoringApiClient({
        authenticatedRequests,
        getSessionGeneration,
      }),
      generation,
      getSessionGeneration,
    };
  }, [authenticatedRequests, generation, getSessionGeneration, session.status]);
}
