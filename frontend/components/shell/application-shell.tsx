"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { useAuth } from "../../features/auth/auth-provider";
import { PageContainer } from "../ui/page-container";
import { AuthenticatedUserMenu } from "./authenticated-user-menu";

export interface ApplicationShellProps {
  readonly children: ReactNode;
  readonly currentSection?: "home" | "quizzes";
}

export function ApplicationShell({
  children,
  currentSection = "home",
}: ApplicationShellProps) {
  const { activeWorkspace, session, user } = useAuth();
  const isApplicationHome = currentSection === "home";
  const isQuizAuthoring = currentSection === "quizzes";
  const showQuizAuthoring =
    activeWorkspace === "TEACHING" && user?.roles.includes("TEACHER");
  const navLinkClasses =
    "inline-flex min-h-11 items-center rounded-lg px-3 py-2 text-sm font-semibold transition-colors duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none";

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b border-border bg-surface">
        <PageContainer className="flex max-w-none flex-col items-start gap-3 py-3 sm:flex-row sm:items-center">
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

          <nav
            aria-label="Primary navigation"
            className="flex flex-wrap gap-1 sm:ml-3"
          >
            <Link
              aria-current={isApplicationHome ? "page" : undefined}
              className={`${navLinkClasses} ${
                isApplicationHome
                  ? "bg-surface-muted text-primary"
                  : "text-foreground-secondary hover:bg-surface-muted hover:text-foreground"
              }`}
              href="/app"
            >
              Application home
            </Link>
            {showQuizAuthoring ? (
              <Link
                aria-current={isQuizAuthoring ? "page" : undefined}
                className={`${navLinkClasses} ${
                  isQuizAuthoring
                    ? "bg-surface-muted text-primary"
                    : "text-foreground-secondary hover:bg-surface-muted hover:text-foreground"
                }`}
                href="/app/quizzes"
              >
                Quiz authoring
              </Link>
            ) : null}
          </nav>

          <AuthenticatedUserMenu />
        </PageContainer>
      </header>

      {session.status === "refreshing" ? (
        <div
          className="border-b border-info/20 bg-info/5 py-2 text-sm text-foreground-secondary"
          role="status"
        >
          <PageContainer className="max-w-none">
            Updating your session…
          </PageContainer>
        </div>
      ) : null}

      <main id="main-content" tabIndex={-1}>
        <PageContainer className="max-w-none py-8 sm:py-10">
          {children}
        </PageContainer>
      </main>
    </div>
  );
}
