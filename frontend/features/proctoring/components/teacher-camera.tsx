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
    idle: t("No media grant has been requested."),
    authorizing: t("Checking fresh Teacher and target-Student authority."),
    "connected-no-camera": t(
      "Authorized subscription connected, but no Student camera track is available.",
    ),
    live: t("An authorized Student camera track is currently subscribed."),
    stopping: t(
      "SDK disconnect and backend revoke intent are in progress. Outcomes are reported separately when they settle.",
    ),
    reconnecting: t(
      "The connection changed. Disconnect and backend revoke cleanup are in progress before fresh authorization.",
    ),
    disconnected: t(
      "Local cleanup finished. Separate SDK disconnect and backend revoke outcomes appear below.",
    ),
    unavailable: t(
      "Live media is unavailable or not authorized. No camera is being shown.",
    ),
  }[state];

  return (
    <Surface className="overflow-hidden">
      <div className="border-b border-border bg-surface-muted/60 p-5 sm:p-6">
        <p className="text-sm font-semibold text-primary">
          {t("Authorized live camera")}
        </p>
        <h2 className="mt-1 text-xl font-bold">{t("Student camera stream")}</h2>
        <p className="mt-2 text-sm leading-6 text-foreground-secondary">
          {t(
            "This view requests a subscribe-only Teacher grant for this exact server-authorized session. It cannot publish your camera or microphone, administer the room, or discover other sessions.",
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
                "SDK disconnect: {sdk}. Backend revoke: {backend}. A backend result does not prove hard provider revocation.",
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
