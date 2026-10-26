"use client";

import Link from "next/link";
import { useI18n } from "../../lib/i18n/provider";
import { useEffect } from "react";
import { useAuth } from "../../features/auth/auth-provider";
import type { AuthRole } from "../../features/auth/model/authenticated-user";
import type { Workspace } from "../../features/auth/model/workspace";
import { Alert } from "../ui/alert";
import { OUTLINE_LINK_CLASSES } from "../ui/button";
import { HeroOrb } from "../ui/hero-orb";
import { ArrowRightIcon } from "../ui/icons";
import { Surface } from "../ui/surface";
import { useWorkspaceTransition } from "./workspace-transition-overlay";

const roleLabels = {
  STUDENT: "Student",
  TEACHER: "Teacher",
  ADMIN: "Administrator",
} as const satisfies Record<AuthRole, string>;

const workspaceLabels = {
  LEARNING: "Learning workspace",
  TEACHING: "Teaching workspace",
} as const satisfies Record<Workspace, string>;

export function ApplicationHome() {
  const { t } = useI18n();
  const { activeWorkspace, user } = useAuth();
  const { notifyWorkspaceHomeRendered } = useWorkspaceTransition();

  // Ends a pending workspace transition once this home has actually rendered
  // content for the destination workspace (no-op outside a transition). This
  // also covers switching while already on /app: the re-rendered home reports
  // the new workspace and the overlay settles.
  useEffect(() => {
    notifyWorkspaceHomeRendered(activeWorkspace);
  }, [activeWorkspace, notifyWorkspaceHomeRendered]);

  if (user === null) {
    return null;
  }

  // Role and workspace labels guide presentation only. Protected services remain authoritative.
  return (
    <>
      <div className="relative overflow-hidden rounded-2xl border border-primary/10 bg-surface px-6 py-9 shadow-card sm:px-10 sm:py-12">
        <HeroOrb className="-right-12 -top-20 size-80" />
        <div className="relative max-w-2xl">
          <p className="text-xs font-bold uppercase tracking-[0.14em] text-primary">
            {t("Application home")}
          </p>
          <h1 className="mt-3 font-heading text-3xl font-extrabold tracking-[-0.035em] text-foreground sm:text-4xl">
            {t("Your")}{" "}
            <span className="brand-gradient-text">
              {t("Quizopia workspace")}
            </span>
          </h1>
          <p className="mt-4 max-w-xl text-base leading-7 text-foreground-secondary">
            {t(
              "Welcome back, {username}. Access the tools available to your account and keep your learning and teaching work organized.",
              { username: user.username },
            )}
          </p>
          {activeWorkspace === "TEACHING" && user.roles.includes("TEACHER") ? (
            <Link
              className={`${OUTLINE_LINK_CLASSES} mt-6`}
              href="/app/quizzes"
            >
              {t("Open quiz authoring")} <ArrowRightIcon className="size-4" />
            </Link>
          ) : null}
          {activeWorkspace === "LEARNING" && user.roles.includes("STUDENT") ? (
            <Link
              className={`${OUTLINE_LINK_CLASSES} mt-6`}
              href="/app/assessments/join"
            >
              {t("Enter an assessment")} <ArrowRightIcon className="size-4" />
            </Link>
          ) : null}
          {activeWorkspace === "TEACHING" && user.roles.includes("TEACHER") ? (
            <Link
              className={`${OUTLINE_LINK_CLASSES} mt-4 sm:ml-3`}
              href="/app/assessments"
            >
              {t("My Assessments")} <ArrowRightIcon className="size-4" />
            </Link>
          ) : null}
        </div>
      </div>

      <Surface
        aria-labelledby="account-context-title"
        className="mt-6 p-6 sm:p-8"
        role="region"
      >
        <h2
          className="text-xl font-bold text-foreground"
          id="account-context-title"
        >
          {t("Current account context")}
        </h2>
        <dl className="mt-6 grid gap-4 sm:grid-cols-2">
          <div className="rounded-xl border border-border bg-background p-5">
            <dt className="text-sm font-medium text-foreground-muted">
              {t("Current workspace")}
            </dt>
            <dd className="mt-1 font-semibold text-foreground">
              {activeWorkspace === null
                ? t("No workspace")
                : t(workspaceLabels[activeWorkspace])}
            </dd>
          </div>
          <div className="rounded-xl border border-border bg-background p-5">
            <dt className="text-sm font-medium text-foreground-muted">
              {t("Account roles")}
            </dt>
            <dd className="mt-2">
              {user.roles.length === 0 ? (
                <span className="text-sm text-foreground-secondary">
                  {t("No global roles")}
                </span>
              ) : (
                <ul
                  className="flex flex-wrap gap-2"
                  aria-label={t("Account roles")}
                >
                  {user.roles.map((role) => (
                    <li
                      className="rounded-lg border border-border bg-surface-muted px-3 py-1.5 font-mono text-xs font-medium text-foreground-secondary"
                      key={role}
                    >
                      {t(roleLabels[role])}
                    </li>
                  ))}
                </ul>
              )}
            </dd>
          </div>
        </dl>

        {activeWorkspace === null ? (
          <Alert className="mt-6" title={t("No workspace is selected")}>
            {t(
              "Learning and teaching views are available only when the matching account role is present.",
            )}
          </Alert>
        ) : null}
      </Surface>
    </>
  );
}
