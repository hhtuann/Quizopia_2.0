"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { Alert, type AlertVariant } from "../../../components/ui/alert";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { PageContainer } from "../../../components/ui/page-container";
import { Surface } from "../../../components/ui/surface";
import { useAuth } from "../auth-provider";

export type BootstrappingPresentation = "loading" | "unavailable";

export interface AuthenticatedBoundaryProps {
  readonly bootstrappingPresentation?: BootstrappingPresentation;
  readonly children: ReactNode;
}

const loginLinkClasses =
  "mt-6 inline-flex min-h-11 items-center justify-center rounded-lg border border-primary bg-primary px-4 py-2.5 text-sm font-semibold text-foreground-inverse shadow-primary transition-colors duration-200 hover:border-primary-hover hover:bg-primary-hover focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 focus-visible:ring-offset-background motion-reduce:transition-none";

interface SessionMessageProps {
  readonly alertRole?: "alert" | "status";
  readonly alertTitle: string;
  readonly alertVariant?: AlertVariant;
  readonly children: ReactNode;
  readonly description: string;
  readonly showLoginAction?: boolean;
  readonly title: string;
}

function SessionMessage({
  alertRole = "status",
  alertTitle,
  alertVariant = "info",
  children,
  description,
  showLoginAction = false,
  title,
}: SessionMessageProps) {
  return (
    <main id="main-content" tabIndex={-1}>
      <PageContainer className="flex min-h-screen items-center justify-center py-8 sm:py-12">
        <Surface className="w-full max-w-lg p-6 sm:p-8">
          <p className="text-sm font-semibold text-primary">
            Quizopia application
          </p>
          <h1 className="mt-2 text-3xl font-bold tracking-[-0.02em] text-foreground">
            {title}
          </h1>
          <p className="mt-3 max-w-xl text-base leading-7 text-foreground-secondary">
            {description}
          </p>
          <Alert
            className="mt-6"
            role={alertRole}
            title={alertTitle}
            variant={alertVariant}
          >
            {children}
          </Alert>
          {showLoginAction ? (
            <Link className={loginLinkClasses} href="/login">
              Go to sign in
            </Link>
          ) : null}
        </Surface>
      </PageContainer>
    </main>
  );
}

function SessionBootstrapLoading() {
  return (
    <main id="main-content" tabIndex={-1}>
      <PageContainer className="flex min-h-screen items-center justify-center py-8 sm:py-12">
        <Surface className="w-full max-w-lg p-6 sm:p-8">
          <p className="text-sm font-semibold text-primary">
            Quizopia application
          </p>
          <h1 className="mt-2 text-3xl font-bold tracking-[-0.02em] text-foreground">
            Checking your session
          </h1>
          <p className="mt-3 text-base leading-7 text-foreground-secondary">
            Please wait while Quizopia prepares your account.
          </p>
          <div className="mt-6 flex items-center gap-3 rounded-lg border border-border bg-surface-muted p-4 text-sm font-medium text-foreground-secondary">
            <LoadingIndicator label="Checking your session" />
            <span aria-hidden="true">Checking session</span>
          </div>
        </Surface>
      </PageContainer>
    </main>
  );
}

export function AuthenticatedBoundary({
  bootstrappingPresentation = "loading",
  children,
}: AuthenticatedBoundaryProps) {
  const { session } = useAuth();

  if (session.status === "bootstrapping") {
    return bootstrappingPresentation === "unavailable" ? (
      <SessionMessage
        alertTitle="Account access is not available yet"
        description="This version cannot restore a signed-in session."
        showLoginAction
        title="Open the Quizopia application"
      >
        Sign in is required to continue. Account access is not available in this
        version. No session was created.
      </SessionMessage>
    ) : (
      <SessionBootstrapLoading />
    );
  }

  if (session.status === "anonymous") {
    return (
      <SessionMessage
        alertTitle="Account access required"
        description="Sign in to open the Quizopia application."
        showLoginAction
        title="Sign in to continue"
      >
        Your current browser session is not signed in.
      </SessionMessage>
    );
  }

  if (session.status === "session-expired") {
    return (
      <SessionMessage
        alertRole="alert"
        alertTitle="Your session has ended"
        alertVariant="warning"
        description="Sign in again to continue using the Quizopia application."
        showLoginAction
        title="Sign in again"
      >
        Your local session has been cleared. No automatic sign-in was attempted.
      </SessionMessage>
    );
  }

  return children;
}
