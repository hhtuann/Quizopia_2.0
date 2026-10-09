"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { useAuth } from "../../features/auth/auth-provider";
import { QuizopiaLogo } from "../brand/quizopia-logo";
import { APPLICATION_HEADER_GEOMETRY } from "../ui/application-header-geometry";
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
    "inline-flex min-h-10 items-center rounded-full px-4 py-2 text-sm font-semibold transition-colors duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none";

  return (
    <div className="min-h-screen bg-background">
      <header className={APPLICATION_HEADER_GEOMETRY}>
        <Link
          aria-label="Quizopia home"
          className="inline-flex min-h-11 items-center rounded-lg pr-2 transition-colors duration-200 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none"
          href="/app"
        >
          <QuizopiaLogo
            markClassName="size-9 rounded-lg"
            wordmarkClassName="hidden md:flex"
          />
        </Link>

        <nav
          aria-label="Primary navigation"
          className="order-3 flex w-full flex-wrap gap-1 sm:order-none sm:w-auto"
        >
          <Link
            aria-current={isApplicationHome ? "page" : undefined}
            className={`${navLinkClasses} ${
              isApplicationHome
                ? "bg-primary/10 text-primary"
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
                  ? "bg-primary/10 text-primary"
                  : "text-foreground-secondary hover:bg-surface-muted hover:text-foreground"
              }`}
              href="/app/quizzes"
            >
              Quiz authoring
            </Link>
          ) : null}
        </nav>

        <AuthenticatedUserMenu />
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
        <PageContainer className="py-8 sm:py-10">{children}</PageContainer>
      </main>
    </div>
  );
}
