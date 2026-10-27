"use client";

import Link from "next/link";
import { useSyncExternalStore } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button, OUTLINE_LINK_CLASSES } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { formatInstant } from "../../../lib/i18n/core";
import { useI18n } from "../../../lib/i18n/provider";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import { useStudentMonitoring } from "../hooks/use-student-monitoring";
import { canonicalUuid } from "../model/monitoring";
import { StudentCamera } from "./student-camera";
import type { ActivityEvidenceStream } from "../activity/activity-evidence-stream";

export function StudentMonitoringPage({
  attemptId,
}: {
  readonly attemptId: string;
}) {
  return (
    <AuthenticatedBoundary>
      <StudentMonitoringScreen attemptId={attemptId} />
    </AuthenticatedBoundary>
  );
}

export function StudentMonitoringScreen({
  attemptId,
}: {
  readonly attemptId: string;
}) {
  const { user } = useAuth();
  const { t, locale } = useI18n();
  const monitoring = useStudentMonitoring();
  const validAttempt = canonicalUuid.safeParse(attemptId).success;

  return (
    <ApplicationShell>
      <div className="space-y-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-sm font-semibold text-primary">
              {t("Learning · Monitored Attempt")}
            </p>
            <h1 className="mt-1 font-heading text-3xl font-bold">
              {t("Activity and camera monitoring")}
            </h1>
          </div>
          <Link className={OUTLINE_LINK_CLASSES} href="/app">
            {t("Return to application")}
          </Link>
        </div>

        {!user?.roles.includes("STUDENT") ? (
          <Alert title={t("Student access required")} variant="warning">
            {t(
              "A current Student role and ownership of the attempt are required.",
            )}
          </Alert>
        ) : !validAttempt ? (
          <Alert title={t("Invalid Attempt reference")} variant="danger">
            {t("This link is not a valid attempt address.")}
          </Alert>
        ) : monitoring.state.status === "active" ? (
          <>
            <Alert title={t("Monitoring session active")} variant="info">
              {t(
                "Monitoring is verified for this class attempt. Sending monitoring data is separate from saving and submitting; a monitoring problem never changes your saved answers.",
              )}
            </Alert>
            <Surface className="grid gap-4 p-5 text-sm sm:grid-cols-2 lg:grid-cols-4">
              <Binding
                label={t("Session")}
                value={monitoring.state.session.id}
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
                label={t("Deadline")}
                value={formatInstant(
                  locale,
                  monitoring.state.session.deadlineAt,
                  {
                    withTimeZone: true,
                  },
                )}
              />
            </Surface>
            <ActivityDelivery activity={monitoring.state.activity} />
            <StudentCamera
              activity={monitoring.state.activity}
              session={monitoring.state.session}
            />
            <div className="flex justify-end">
              <Button
                onClick={() => void monitoring.stop()}
                variant="neutral-outline"
              >
                {t("End monitoring")}
              </Button>
            </div>
          </>
        ) : (
          <Surface className="p-6 sm:p-8">
            <h2 className="text-xl font-bold">
              {t("Before monitoring begins")}
            </h2>
            <div className="mt-4 max-w-3xl space-y-3 text-sm leading-6 text-foreground-secondary">
              <p>
                {t(
                  "Quizopia will verify this is your current, time-limited, monitored class attempt. PUBLIC and untimed attempts are not eligible.",
                )}
              </p>
              <p>
                {t(
                  "When verified, this page reports question navigation, answer interactions, tab visibility and focus, fullscreen, network, duplicate tabs, and camera status. It does not collect answer contents, unrelated URLs, browser history, keystrokes, your screen, or microphone.",
                )}
              </p>
              <p>
                {t(
                  "Browser observations can be incomplete and are not proof of misconduct. Nothing on this page automatically changes a grade or imposes a sanction.",
                )}
              </p>
            </div>
            {monitoring.state.status === "unavailable" ? (
              <Alert
                className="mt-5"
                title={t("Monitoring unavailable")}
                variant="warning"
              >
                {t(
                  "Access was denied or a required feature is unavailable. Nothing is being recorded.",
                )}
              </Alert>
            ) : null}
            {monitoring.state.status === "ended" ? (
              <Alert
                className="mt-5"
                title={t("Monitoring ended")}
                variant="info"
              >
                {t("Monitoring was stopped. Your attempt was not changed.")}
              </Alert>
            ) : null}
            <Button
              className="mt-6"
              disabled={monitoring.state.status === "starting"}
              isLoading={monitoring.state.status === "starting"}
              loadingLabel={t("Verifying monitoring eligibility")}
              onClick={() => void monitoring.verifyAndStart(attemptId)}
            >
              {t("Verify eligibility and begin monitoring")}
            </Button>
          </Surface>
        )}
      </div>
    </ApplicationShell>
  );
}

function ActivityDelivery({
  activity,
}: {
  readonly activity: ActivityEvidenceStream;
}) {
  const { t } = useI18n();
  const snapshot = useSyncExternalStore(
    activity.subscribe,
    activity.getSnapshot,
    activity.getSnapshot,
  );
  const variant =
    snapshot.delivery === "idle"
      ? "success"
      : snapshot.delivery === "delivering"
        ? "info"
        : "warning";
  return (
    <Alert title={t("Activity evidence delivery")} variant={variant}>
      {t(
        "State: {state}. Pending: {pending}. Dropped: {dropped}. Delivery never affects saving or submitting.",
        {
          state: snapshot.delivery,
          pending: snapshot.pendingCount,
          dropped: snapshot.droppedCount,
        },
      )}
    </Alert>
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
