"use client";

import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { useI18n } from "../../../lib/i18n/provider";
import { useTeacherCamera } from "../hooks/use-teacher-camera";
import type { MonitoringSession } from "../model/monitoring";

export function TeacherCamera({
  session,
}: {
  readonly session: MonitoringSession;
}) {
  const { t } = useI18n();
  const { state, cleanupReport, videoRef, connect, reconnect, stop } =
    useTeacherCamera({ session });
  const statusCopy = {
    idle: t("No camera access has been requested yet."),
    authorizing: t("Checking current access for you and the student."),
    "connected-no-camera": t(
      "Connected, but the student's camera is not available.",
    ),
    live: t("You are viewing the student's camera."),
    stopping: t("Disconnecting. Results are reported below when finished."),
    reconnecting: t(
      "The connection changed. Cleaning up before requesting fresh access.",
    ),
    disconnected: t("Cleanup finished — see the results below."),
    unavailable: t(
      "The camera is unavailable or not authorized. Nothing is being shown.",
    ),
  }[state];

  return (
    <Surface className="overflow-hidden">
      <div className="border-b border-border bg-surface-muted/60 p-5 sm:p-6">
        <p className="text-sm font-semibold text-primary">
          {t("Live monitoring")}
        </p>
        <h2 className="mt-1 text-xl font-bold">{t("Student camera stream")}</h2>
        <p className="mt-2 text-sm leading-6 text-foreground-secondary">
          {t(
            "This view can only watch this one session. It cannot use your camera or microphone, control the room, or find other sessions.",
          )}
        </p>
      </div>
      <div className="grid gap-5 p-5 sm:p-6 lg:grid-cols-[minmax(0,1fr)_18rem]">
        <div className="relative aspect-video overflow-hidden rounded-xl bg-slate-950">
          <video
            aria-label={t("Authorized Student camera")}
            autoPlay
            className="h-full w-full object-cover"
            playsInline
            ref={videoRef}
          />
          <span className="absolute left-3 top-3 rounded-full bg-slate-950/80 px-3 py-1.5 text-xs font-semibold text-white">
            {state === "live" ? t("Camera visible") : t("No live camera")}
          </span>
        </div>
        <div className="space-y-4">
          <Alert
            title={
              state === "live" ? t("Live subscription") : t("Camera status")
            }
            variant={state === "live" ? "success" : "info"}
          >
            {statusCopy}
          </Alert>
          {cleanupReport !== null &&
          ["stopping", "reconnecting", "disconnected", "unavailable"].includes(
            state,
          ) ? (
            <Alert title={t("Cleanup results")} variant="info">
              {t(
                "Connection closed: {sdk}. Access revocation: {backend} (records intent only).",
                {
                  sdk: cleanupReport.sdkDisconnect,
                  backend: cleanupReport.backendRevoke,
                },
              )}
            </Alert>
          ) : null}
          {state === "idle" ||
          state === "disconnected" ||
          state === "unavailable" ? (
            <Button onClick={() => void connect()}>
              {t("Authorize camera view")}
            </Button>
          ) : null}
          {state === "reconnecting" ? (
            <Button onClick={() => void reconnect()} variant="secondary">
              {t("Reauthorize subscription")}
            </Button>
          ) : null}
          {state === "live" || state === "connected-no-camera" ? (
            <Button onClick={() => void stop()} variant="neutral-outline">
              {t("Disconnect view")}
            </Button>
          ) : null}
        </div>
      </div>
    </Surface>
  );
}
