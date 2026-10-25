"use client";
import { useMemo } from "react";
import { useAuth } from "../auth/auth-provider";
import { createClassroomApiClient } from "./api/classroom-api-client";

export function useClassroomClient() {
  const { authenticatedRequests, getSessionGeneration, user } = useAuth();
  const generation = getSessionGeneration();
  return useMemo(
    () =>
      authenticatedRequests && generation !== null && user
        ? createClassroomApiClient({
            authenticatedRequests,
            getSessionGeneration,
            userId: user.id,
          })
        : null,
    [authenticatedRequests, getSessionGeneration, generation, user],
  );
}
