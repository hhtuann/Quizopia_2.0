"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button, OUTLINE_LINK_CLASSES } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
import { useI18n } from "../../../lib/i18n/provider";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import { useTeacherMonitoring } from "../hooks/use-teacher-monitoring";
import { canonicalUuid } from "../model/monitoring";
import { TeacherCamera } from "./teacher-camera";
import { TeacherTimeline } from "./teacher-timeline";

export function TeacherMonitoringPage({
  attemptId,
}: {
  readonly attemptId?: string;
}) {
  return (
    <AuthenticatedBoundary>
      <SessionScopedTeacherMonitoring attemptId={attemptId} />
    </AuthenticatedBoundary>
  );
}

function SessionScopedTeacherMonitoring({
  attemptId,
}: {
  readonly attemptId?: string;
}) {
  const { getSessionGeneration, user } = useAuth();
  const scope = `${user?.id ?? "none"}:${getSessionGeneration() ?? "none"}:${attemptId ?? "lookup"}`;
  return <TeacherMonitoringScreen attemptId={attemptId} key={scope} />;
}

export function TeacherMonitoringScreen({
  attemptId,
}: {
  readonly attemptId?: string;
}) {
  const router = useRouter();
  const { activeWorkspace, switchWorkspace, user } = useAuth();
  const { t } = useI18n();
  const [knownAttemptId, setKnownAttemptId] = useState(attemptId ?? "");
  const [invalid, setInvalid] = useState(false);
  const monitoring = useTeacherMonitoring(
    attemptId && canonicalUuid.safeParse(attemptId).success
      ? attemptId
      : undefined,
  );

  function submitLookup(event: FormEvent) {
    event.preventDefault();
    if (!canonicalUuid.safeParse(knownAttemptId).success) {
      setInvalid(true);
      return;
    }
    setInvalid(false);
    router.push(`/app/monitoring/teacher/attempts/${knownAttemptId}`);
  }

  return (
    <ApplicationShell>
      <div className="space-y-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-sm font-semibold text-primary">
              {t("Teaching · Monitoring")}
            </p>
            <h1 className="mt-1 font-heading text-3xl font-bold">
              {t("Authorized Attempt monitoring")}
            </h1>
          </div>
          <Link className={OUTLINE_LINK_CLASSES} href="/app">
            {t("Return to application")}
          </Link>
        </div>

        {!user?.roles.includes("TEACHER") ? (
          <Alert title={t("Teacher access required")} variant="warning">
            {t(
              "The server requires a current Teacher role, Publication ownership, and Classroom authority. Knowing an Attempt ID is not authorization.",
            )}
          </Alert>
        ) : activeWorkspace !== "TEACHING" ? (
          <Surface className="p-6">
            <h2 className="text-xl font-bold">
              {t("Open the Teaching workspace")}
            </h2>
            <p className="mt-2 text-foreground-secondary">
              {t(
                "Workspace selection only changes presentation; server authorization remains mandatory.",
              )}
            </p>
            <Button
              className="mt-5"
              onClick={() => switchWorkspace("TEACHING")}
            >
              {t("Switch to Teaching workspace")}
            </Button>
          </Surface>
        ) : (
          <>
            <Surface className="p-6">
              <h2 className="text-xl font-bold">{t("Open a known Attempt")}</h2>
              <p className="mt-2 max-w-3xl text-sm leading-6 text-foreground-secondary">
                {t(
                  "No global monitoring roster API exists. Enter an exact Attempt ID from the authorized Teacher Results experience; the backend performs a fresh resource check before revealing whether a session exists.",
                )}
              </p>
              <form
                className="mt-4 flex max-w-3xl flex-col gap-4 sm:flex-row sm:items-end"
                onSubmit={submitLookup}
              >
                <div className="flex-1">
                  <TextField
                    error={
                      invalid
                        ? t("Enter a canonical lowercase Attempt UUID.")
                        : undefined
                    }
                    label={t("Attempt ID")}
                    onChange={(event) => setKnownAttemptId(event.target.value)}
                    required
                    value={knownAttemptId}
                  />
                </div>
                <Button type="submit">{t("Check authorization")}</Button>
              </form>
            </Surface>

            {monitoring.state.status === "loading" ? (
              <Alert title={t("Checking current authority")}>
                {t("Loading the authorized session and first timeline page.")}
              </Alert>
            ) : monitoring.state.status === "not-found" ? (
              <Alert title={t("Session not accessible")} variant="warning">
                {t(
                  "No authorized monitoring session is available for that Attempt. The response intentionally does not reveal whether a foreign resource exists.",
                )}
              </Alert>
            ) : monitoring.state.status === "unavailable" ? (
              <Alert title={t("Monitoring unavailable")} variant="warning">
                {t(
                  "Current authority or a required service could not be verified. Access fails closed and no cached grant is used.",
                )}
              </Alert>
            ) : monitoring.state.status === "ready" ? (
              <>
                <Surface className="grid gap-4 p-5 text-sm sm:grid-cols-2 lg:grid-cols-4">
                  <Binding
                    label={t("Session status")}
                    value={monitoring.state.session.status}
                  />
                  <Binding
                    label={t("Publication")}
                    value={monitoring.state.session.publicationId}
                  />
                  <Binding
                    label={t("Classroom")}
                    value={monitoring.state.session.classroomId}
                  />
                  <Binding
                    label={t("Student")}
                    value={monitoring.state.session.studentId}
                  />
                </Surface>
                <TeacherCamera session={monitoring.state.session} />
                <Surface className="p-5 sm:p-6">
                  <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
                    <div>
                      <h2 className="text-xl font-bold">
                        {t("Activity timeline")}
                      </h2>
                      <p className="mt-1 text-sm text-foreground-secondary">
                        {t(
                          "Server ordinal controls ordering. Source labels distinguish browser reports from Assessment persistence facts.",
                        )}
                      </p>
                    </div>
                    <Button
                      onClick={() => void monitoring.refresh()}
                      variant="neutral-outline"
                    >
                      {t("Refresh authority and timeline")}
                    </Button>
                  </div>
                  <TeacherTimeline items={monitoring.state.timeline} />
                  {monitoring.state.nextAfter !== null ? (
                    <Button
                      className="mt-5"
                      isLoading={monitoring.state.loadingMore}
                      loadingLabel={t("Loading more evidence")}
                      onClick={() => void monitoring.loadMore()}
                      variant="secondary"
                    >
                      {t("Load more")}
                    </Button>
                  ) : null}
                </Surface>
              </>
            ) : null}
          </>
        )}
      </div>
    </ApplicationShell>
  );
}

function Binding({
  label,
  value,
}: {
  readonly label: string;
  readonly value: string;
}) {
  return (
    <div className="min-w-0">
      <p className="font-semibold text-foreground">{label}</p>
      <p className="mt-1 break-all text-foreground-muted">{value}</p>
    </div>
  );
}
