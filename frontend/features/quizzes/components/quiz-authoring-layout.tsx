"use client";

import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import { useTeacherEnablement } from "../../auth/hooks/use-teacher-enablement";

function TeacherAuthoringBoundary({
  children,
}: {
  readonly children: ReactNode;
}) {
  const { activeWorkspace, switchWorkspace, user } = useAuth();
  const { isPending, notice, requestTeacherEnablement } =
    useTeacherEnablement();

  if (user === null) {
    return null;
  }
  if (!user.roles.includes("TEACHER")) {
    return (
      <Surface className="max-w-2xl p-6 sm:p-8">
        <h1 className="font-heading text-2xl font-normal text-foreground">
          Teacher access required
        </h1>
        <Alert
          className="mt-5"
          title="Register as teacher to author quizzes"
          variant={notice?.kind === "error" ? "danger" : "warning"}
        >
          {notice?.message ??
            "Your verified account can add Teacher access while retaining its Student role."}
        </Alert>
        <Button
          className="mt-6"
          isLoading={isPending}
          loadingLabel="Registering as teacher"
          onClick={() => void requestTeacherEnablement()}
        >
          Register as teacher
        </Button>
      </Surface>
    );
  }
  if (activeWorkspace !== "TEACHING") {
    return (
      <Surface className="max-w-2xl p-6 sm:p-8">
        <h1 className="font-heading text-2xl font-normal text-foreground">
          Open the Teaching workspace
        </h1>
        {notice?.kind === "success" ? (
          <Alert
            className="mt-5"
            title="Teacher access is ready"
            variant="success"
          >
            Your authoritative account now includes Student and Teacher access.
            Open the Teaching workspace to continue.
          </Alert>
        ) : (
          <p className="mt-3 text-base leading-7 text-foreground-secondary">
            Quiz authoring belongs to your Teaching workspace. Switching
            workspace changes presentation only; your account roles remain
            unchanged.
          </p>
        )}
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
  const pathname = usePathname();
  const isFocusedEditor = pathname !== "/app/quizzes";

  if (isFocusedEditor) {
    return (
      <AuthenticatedBoundary>
        <main
          className="flex h-dvh min-h-[32rem] min-w-0 flex-col overflow-hidden bg-background"
          id="main-content"
          tabIndex={-1}
        >
          <TeacherAuthoringBoundary>{children}</TeacherAuthoringBoundary>
        </main>
      </AuthenticatedBoundary>
    );
  }

  return (
    <AuthenticatedBoundary>
      <ApplicationShell currentSection="quizzes">
        <TeacherAuthoringBoundary>{children}</TeacherAuthoringBoundary>
      </ApplicationShell>
    </AuthenticatedBoundary>
  );
}
