"use client";

import { useAuth } from "../../features/auth/auth-provider";
import type { AuthRole } from "../../features/auth/model/authenticated-user";
import type { Workspace } from "../../features/auth/model/workspace";
import { Alert } from "../ui/alert";
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
      <div className="max-w-2xl">
        <p className="text-sm font-semibold text-primary">Application home</p>
        <h1 className="mt-2 text-3xl font-bold tracking-[-0.02em] text-foreground">
          Your Quizopia workspace
        </h1>
        <p className="mt-3 text-base leading-7 text-foreground-secondary">
          This shared application frame is ready for Quizopia product tools as
          they become available.
        </p>
      </div>

      <Surface
        aria-labelledby="account-context-title"
        className="mt-8 max-w-2xl p-6 sm:p-8"
        role="region"
      >
        <h2
          className="text-xl font-semibold text-foreground"
          id="account-context-title"
        >
          Current account context
        </h2>
        <dl className="mt-5 space-y-5">
          <div>
            <dt className="text-sm font-medium text-foreground-muted">
              Current workspace
            </dt>
            <dd className="mt-1 font-semibold text-foreground">
              {activeWorkspace === null
                ? "No workspace"
                : workspaceLabels[activeWorkspace]}
            </dd>
          </div>
          <div>
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
                      className="rounded-lg border border-border bg-surface-muted px-3 py-1.5 text-sm font-medium text-foreground-secondary"
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
