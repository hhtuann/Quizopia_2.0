"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { useAuth } from "../../features/auth/auth-provider";
import { Button } from "../ui/button";
import { PageContainer } from "../ui/page-container";
import { WorkspaceSwitcher } from "./workspace-switcher";

export interface ApplicationShellProps {
  readonly children: ReactNode;
}

export function ApplicationShell({ children }: ApplicationShellProps) {
  const {
    activeWorkspace,
    availableWorkspaces,
    clearLocalSession,
    session,
    switchWorkspace,
  } = useAuth();

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b border-border bg-surface">
        <PageContainer className="flex flex-col items-start gap-3 py-3 sm:flex-row sm:items-center">
          <Link
            className="inline-flex min-h-11 items-center gap-3 rounded-lg pr-2 text-sm font-bold text-foreground transition-colors duration-200 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none"
            href="/"
          >
            <span
              aria-hidden="true"
              className="flex size-9 items-center justify-center rounded-lg bg-primary text-base text-foreground-inverse shadow-primary"
            >
              Q
            </span>
            Quizopia 2.0
          </Link>

          <nav aria-label="Primary navigation" className="sm:ml-3">
            <Link
              aria-current="page"
              className="inline-flex min-h-11 items-center rounded-lg bg-surface-muted px-3 py-2 text-sm font-semibold text-primary transition-colors duration-200 hover:bg-surface-strong focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none"
              href="/app"
            >
              Application home
            </Link>
          </nav>

          <Button
            className="sm:ml-auto"
            onClick={clearLocalSession}
            variant="secondary"
          >
            Sign out
          </Button>
        </PageContainer>

        <WorkspaceSwitcher
          activeWorkspace={activeWorkspace}
          availableWorkspaces={availableWorkspaces}
          onSwitch={switchWorkspace}
        />
      </header>

      {session.status === "refreshing" ? (
        <div
          className="border-b border-info/20 bg-info/5 py-2 text-sm text-foreground-secondary"
          role="status"
        >
          <PageContainer>Updating your session…</PageContainer>
        </div>
      ) : null}

      <main id="main-content" tabIndex={-1}>
        <PageContainer className="py-8 sm:py-10">{children}</PageContainer>
      </main>
    </div>
  );
}
