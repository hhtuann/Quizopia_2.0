"use client";

import Link from "next/link";
import { useAuth } from "../../features/auth/auth-provider";
import type { AuthRole } from "../../features/auth/model/authenticated-user";
import type { Workspace } from "../../features/auth/model/workspace";
import { Alert } from "../ui/alert";
import { OUTLINE_LINK_CLASSES } from "../ui/button";
import { ArrowRightIcon } from "../ui/icons";
import { Surface } from "../ui/surface";

const roleLabels: Record<AuthRole, string> = {
  STUDENT: "Student",
  TEACHER: "Teacher",
  ADMIN: "Administrator",
};

const workspaceLabels: Record<Workspace, string> = {
  LEARNING: "Learning workspace",
  TEACHING: "Teaching workspace",
};

export function ApplicationHome() {
  const { activeWorkspace, user } = useAuth();

  if (user === null) {
    return null;
  }

  // Role and workspace labels guide presentation only. Protected services remain authoritative.
  return (
    <>
      <div className="relative overflow-hidden rounded-2xl border border-primary/10 bg-surface px-6 py-9 shadow-card sm:px-10 sm:py-12">
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -right-12 -top-20 size-80 rounded-full bg-primary/5 blur-3xl"
        />
        <div className="relative max-w-2xl">
          <p className="text-xs font-bold uppercase tracking-[0.14em] text-primary">
            Application home
          </p>
          <h1 className="mt-3 font-heading text-3xl font-extrabold tracking-[-0.035em] text-foreground sm:text-4xl">
            Your <span className="brand-gradient-text">Quizopia workspace</span>
          </h1>
          <p className="mt-4 max-w-xl text-base leading-7 text-foreground-secondary">
            Welcome back, {user.username}. Access the tools available to your
            account and keep your learning and teaching work organized.
          </p>
          {activeWorkspace === "TEACHING" && user.roles.includes("TEACHER") ? (
            <Link
              className={`${OUTLINE_LINK_CLASSES} mt-6`}
              href="/app/quizzes"
            >
              Open quiz authoring <ArrowRightIcon className="size-4" />
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
          Current account context
        </h2>
        <dl className="mt-6 grid gap-4 sm:grid-cols-2">
          <div className="rounded-xl border border-border bg-background p-5">
            <dt className="text-sm font-medium text-foreground-muted">
              Current workspace
            </dt>
            <dd className="mt-1 font-semibold text-foreground">
              {activeWorkspace === null
                ? "No workspace"
                : workspaceLabels[activeWorkspace]}
            </dd>
          </div>
          <div className="rounded-xl border border-border bg-background p-5">
            <dt className="text-sm font-medium text-foreground-muted">
              Account roles
            </dt>
            <dd className="mt-2">
              {user.roles.length === 0 ? (
                <span className="text-sm text-foreground-secondary">
                  No global roles
                </span>
              ) : (
                <ul className="flex flex-wrap gap-2" aria-label="Account roles">
                  {user.roles.map((role) => (
                    <li
                      className="rounded-lg border border-border bg-surface-muted px-3 py-1.5 font-mono text-xs font-medium text-foreground-secondary"
                      key={role}
                    >
                      {roleLabels[role]}
                    </li>
                  ))}
                </ul>
              )}
            </dd>
          </div>
        </dl>

        {activeWorkspace === null ? (
          <Alert className="mt-6" title="No workspace is selected">
            Learning and teaching views are available only when the matching
            account role is present.
          </Alert>
        ) : null}
      </Surface>
    </>
  );
}
