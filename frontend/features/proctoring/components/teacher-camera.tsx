"use client";

import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { useTeacherCamera } from "../hooks/use-teacher-camera";
import type { MonitoringSession } from "../model/monitoring";

export function TeacherCamera({
  session,
}: {
  readonly session: MonitoringSession;
}) {
  const { state, cleanupReport, videoRef, connect, reconnect, stop } =
    useTeacherCamera({ session });
  const statusCopy = {
    idle: "No media grant has been requested.",
    authorizing: "Checking fresh Teacher and target-Student authority.",
    "connected-no-camera":
      "Authorized subscription connected, but no Student camera track is available.",
    live: "An authorized Student camera track is currently subscribed.",
    stopping:
      "SDK disconnect and backend revoke intent are in progress. Outcomes are reported separately when they settle.",
    reconnecting:
      "The connection changed. Disconnect and backend revoke cleanup are in progress before fresh authorization.",
    disconnected:
      "Local cleanup finished. Separate SDK disconnect and backend revoke outcomes appear below.",
    unavailable:
      "Live media is unavailable or not authorized. No camera is being shown.",
  }[state];

  return (
    <Surface className="overflow-hidden">
      <div className="border-b border-border bg-surface-muted/60 p-5 sm:p-6">
        <p className="text-sm font-semibold text-primary">
          Authorized live camera
        </p>
        <h2 className="mt-1 text-xl font-bold">Student camera stream</h2>
        <p className="mt-2 text-sm leading-6 text-foreground-secondary">
          This view requests a subscribe-only Teacher grant for this exact
          server-authorized session. It cannot publish your camera or
          microphone, administer the room, or discover other sessions.
        </p>
      </div>
      <div className="grid gap-5 p-5 sm:p-6 lg:grid-cols-[minmax(0,1fr)_18rem]">
        <div className="relative aspect-video overflow-hidden rounded-xl bg-slate-950">
          <video
            aria-label="Authorized Student camera"
            autoPlay
            className="h-full w-full object-cover"
            playsInline
            ref={videoRef}
          />
          <span className="absolute left-3 top-3 rounded-full bg-slate-950/80 px-3 py-1.5 text-xs font-semibold text-white">
            {state === "live" ? "Camera visible" : "No live camera"}
          </span>
        </div>
        <div className="space-y-4">
          <Alert
            title={state === "live" ? "Live subscription" : "Camera status"}
            variant={state === "live" ? "success" : "info"}
          >
            {statusCopy}
          </Alert>
          {cleanupReport !== null &&
          ["stopping", "reconnecting", "disconnected", "unavailable"].includes(
            state,
          ) ? (
            <Alert title="Cleanup results" variant="info">
              SDK disconnect: {cleanupReport.sdkDisconnect}. Backend revoke:{" "}
              {cleanupReport.backendRevoke}. A backend result does not prove
              hard provider revocation.
            </Alert>
          ) : null}
          {state === "idle" ||
          state === "disconnected" ||
          state === "unavailable" ? (
            <Button onClick={() => void connect()}>
              Authorize camera view
            </Button>
          ) : null}
          {state === "reconnecting" ? (
            <Button onClick={() => void reconnect()} variant="secondary">
              Reauthorize subscription
            </Button>
          ) : null}
          {state === "live" || state === "connected-no-camera" ? (
            <Button onClick={() => void stop()} variant="neutral-outline">
              Disconnect view
            </Button>
          ) : null}
        </div>
      </div>
    </Surface>
  );
}
