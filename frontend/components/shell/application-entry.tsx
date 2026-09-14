"use client";

import { AuthenticatedBoundary } from "../../features/auth/components/authenticated-boundary";
import { ApplicationHome } from "./application-home";
import { ApplicationShell } from "./application-shell";

export function ApplicationEntry() {
  return (
    <AuthenticatedBoundary bootstrappingPresentation="unavailable">
      <ApplicationShell>
        <ApplicationHome />
      </ApplicationShell>
    </AuthenticatedBoundary>
  );
}
