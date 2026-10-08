"use client";

import { useRef, useState } from "react";
import { useAuth } from "../auth-provider";

export type TeacherEnablementNotice = {
  readonly kind: "error" | "success" | "warning";
  readonly message: string;
} | null;

export function useTeacherEnablement() {
  const { enableTeacher } = useAuth();
  const inFlight = useRef(false);
  const [isPending, setIsPending] = useState(false);
  const [notice, setNotice] = useState<TeacherEnablementNotice>(null);

  function clearNotice() {
    setNotice(null);
  }

  async function requestTeacherEnablement() {
    if (inFlight.current) {
      return false;
    }

    inFlight.current = true;
    setNotice(null);
    setIsPending(true);
    let result: Awaited<ReturnType<typeof enableTeacher>>;
    try {
      result = await enableTeacher();
    } catch {
      inFlight.current = false;
      setIsPending(false);
      setNotice({
        kind: "error",
        message:
          "Teacher registration could not be completed. Your current access has not changed. Try again.",
      });
      return false;
    }
    inFlight.current = false;
    setIsPending(false);

    if (result.ok) {
      setNotice({
        kind: "success",
        message:
          "Teacher access is ready. You can now switch to the Teaching workspace.",
      });
      return true;
    }

    if (result.error.kind === "session-update-failure") {
      setNotice({
        kind: "warning",
        message:
          "Teacher access may have been enabled, but your session could not be updated. Try Register as teacher again to refresh your access.",
      });
      return false;
    }

    if (
      result.error.kind === "api-error" &&
      result.error.error.status === 403
    ) {
      setNotice({
        kind: "error",
        message: "Teacher registration is not available for this account.",
      });
      return false;
    }

    if (result.error.kind !== "authentication-failure") {
      setNotice({
        kind: "error",
        message:
          "Teacher registration could not be completed. Your current access has not changed. Try again.",
      });
    }
    return false;
  }

  return {
    clearNotice,
    isPending,
    notice,
    requestTeacherEnablement,
  } as const;
}
