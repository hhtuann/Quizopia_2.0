"use client";

import Link from "next/link";
import { useSyncExternalStore } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button, OUTLINE_LINK_CLASSES } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
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
  const monitoring = useStudentMonitoring();
  const validAttempt = canonicalUuid.safeParse(attemptId).success;

  return (
    <ApplicationShell>
      <div className="space-y-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-sm font-semibold text-primary">
              Learning · Monitored Attempt
            </p>
            <h1 className="mt-1 font-heading text-3xl font-bold">
              Activity and camera monitoring
            </h1>
          </div>
          <Link className={OUTLINE_LINK_CLASSES} href="/app">
            Return to application
          </Link>
        </div>

        {!user?.roles.includes("STUDENT") ? (
          <Alert title="Student access required" variant="warning">
            A current Student role and authoritative Attempt ownership are
            required.
          </Alert>
        ) : !validAttempt ? (
          <Alert title="Invalid Attempt reference" variant="danger">
            This route requires a canonical lowercase Attempt UUID.
          </Alert>
        ) : monitoring.state.status === "active" ? (
          <>
            <Alert title="Monitoring session active" variant="info">
              The server verified this bounded CLASS Attempt and returned the
              bindings shown below. Activity delivery is separate from
              Assessment SAVE and SUBMIT; a monitoring outage never changes
              answer-save status.
            </Alert>
            <Surface className="grid gap-4 p-5 text-sm sm:grid-cols-2 lg:grid-cols-4">
              <Binding label="Session" value={monitoring.state.session.id} />
              <Binding
                label="Publication"
                value={monitoring.state.session.publicationId}
              />
              <Binding
                label="Classroom"
                value={monitoring.state.session.classroomId}
              />
              <Binding
                label="Deadline"
                value={monitoring.state.session.deadlineAt}
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
                End local monitoring connection
              </Button>
            </div>
          </>
        ) : (
          <Surface className="p-6 sm:p-8">
            <h2 className="text-xl font-bold">Before monitoring begins</h2>
            <div className="mt-4 max-w-3xl space-y-3 text-sm leading-6 text-foreground-secondary">
              <p>
                Quizopia will ask the server to verify that this is your
                current, active, bounded, monitoring-enabled CLASS Attempt.
                PUBLIC and durationless Attempts are not eligible.
              </p>
              <p>
                Once verified, this page can report its own question-navigation,
                answer-interaction metadata, visibility, focus, fullscreen,
                network, duplicate-tab, and camera technical states. It does not
                collect answer contents, unrelated URLs, browser history,
                keystrokes, your screen, or microphone.
              </p>
              <p>
                Browser observations can be incomplete or manipulated and are
                not proof of misconduct. No event on this page automatically
                changes a grade, submits an Attempt, or imposes a sanction.
              </p>
            </div>
            {monitoring.state.status === "unavailable" ? (
              <Alert
                className="mt-5"
                title="Monitoring unavailable"
                variant="warning"
              >
                The server denied access or a required authority/provider
                feature is unavailable. No activity collection or camera capture
                is active.
              </Alert>
            ) : null}
            {monitoring.state.status === "ended" ? (
              <Alert className="mt-5" title="Monitoring ended" variant="info">
                Local monitoring resources were released. This did not alter the
                authoritative Attempt.
              </Alert>
            ) : null}
            <Button
              className="mt-6"
              disabled={monitoring.state.status === "starting"}
              isLoading={monitoring.state.status === "starting"}
              loadingLabel="Verifying monitoring eligibility"
              onClick={() => void monitoring.verifyAndStart(attemptId)}
            >
              Verify eligibility and begin monitoring
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
    <Alert title="Activity evidence delivery" variant={variant}>
      State: {snapshot.delivery}. Pending: {snapshot.pendingCount}. Dropped
      because the in-memory queue was full: {snapshot.droppedCount}. Delivery
      status never changes Assessment SAVE or SUBMIT.
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
