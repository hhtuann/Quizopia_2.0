"use client";

import type { ReactNode } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";

function TeacherAuthoringBoundary({
  children,
}: {
  readonly children: ReactNode;
}) {
  const { activeWorkspace, switchWorkspace, user } = useAuth();

  if (user === null) {
    return null;
  }
  if (!user.roles.includes("TEACHER")) {
    return (
      <Surface className="max-w-2xl p-6 sm:p-8">
        <h1 className="text-2xl font-bold text-foreground">
          Teacher access required
        </h1>
        <Alert
          className="mt-5"
          title="Quiz authoring is not available for this account"
          variant="warning"
        >
          Your account does not currently have the Teacher role. Teacher
          self-enablement is a separate product flow and is not available in
          this authoring workstream.
        </Alert>
      </Surface>
    );
  }
  if (activeWorkspace !== "TEACHING") {
    return (
      <Surface className="max-w-2xl p-6 sm:p-8">
        <h1 className="text-2xl font-bold text-foreground">
          Open the Teaching workspace
        </h1>
        <p className="mt-3 text-base leading-7 text-foreground-secondary">
          Quiz authoring belongs to your Teaching workspace. Switching workspace
          changes presentation only; your account roles remain unchanged.
        </p>
        <Button className="mt-6" onClick={() => switchWorkspace("TEACHING")}>
          Switch to Teaching
        </Button>
      </Surface>
    );
  }
  return children;
}

export function QuizAuthoringLayout({
  children,
}: {
  readonly children: ReactNode;
}) {
  return (
    <AuthenticatedBoundary>
      <ApplicationShell currentSection="quizzes">
        <TeacherAuthoringBoundary>{children}</TeacherAuthoringBoundary>
      </ApplicationShell>
    </AuthenticatedBoundary>
  );
}
