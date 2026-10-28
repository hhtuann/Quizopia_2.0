"use client";

import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { useI18n } from "../../../lib/i18n/provider";
import type { ActivityEvidenceStream } from "../activity/activity-evidence-stream";
import type { MonitoringSession } from "../model/monitoring";
import type { MediaCleanupReport } from "../model/media";
import { useStudentCamera } from "../hooks/use-student-camera";

export function StudentCamera(props: {
  readonly session: MonitoringSession;
  readonly activity: ActivityEvidenceStream;
}) {
  const { t } = useI18n();
  const { state, cleanupReport, previewRef, start, reconnect, stop } =
    useStudentCamera(props);
  const isLive = state === "live";
  const canStart = [
    "idle",
    "permission-denied",
    "device-missing",
    "device-error",
    "unavailable",
    "disconnected",
  ].includes(state);

  return (
    <Surface className="overflow-hidden">
      <div className="border-b border-border bg-surface-muted/60 p-5 sm:p-6">
        <p className="text-sm font-semibold text-primary">
          {t("Student camera")}
        </p>
        <h2 className="mt-1 text-xl font-bold">
          {t("Share your camera deliberately")}
        </h2>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-foreground-secondary">
          {t(
            "Your teacher may view this camera while the monitored attempt is active. Quizopia does not request your microphone, screen, unrelated tab URLs, keyboard input, or browser history. Allowing the camera is not, by itself, a disciplinary decision or proof of wrongdoing.",
          )}
        </p>
      </div>
      <div className="grid gap-5 p-5 sm:p-6 lg:grid-cols-[minmax(0,1fr)_18rem]">
        <div className="relative aspect-video overflow-hidden rounded-xl bg-slate-950">
          <video
            aria-label={t("Your camera preview")}
            autoPlay
            className="h-full w-full object-cover [transform:scaleX(-1)]"
            muted
            playsInline
            ref={previewRef}
          />
          <div className="absolute left-3 top-3 rounded-full bg-slate-950/80 px-3 py-1.5 text-xs font-semibold text-white">
            {isLive ? t("Live camera publishing") : t("Camera not live")}
          </div>
        </div>
        <div className="space-y-4">
          <CameraStatus state={state} />
          {cleanupReport !== null &&
          [
            "reconnecting",
            "disconnected",
            "unavailable",
            "device-error",
          ].includes(state) ? (
            <CleanupStatus report={cleanupReport} />
          ) : null}
          {canStart ? (
            <Button onClick={() => void start()}>
              {t("Request camera and connect")}
            </Button>
          ) : null}
          {state === "reconnecting" || state === "disconnected" ? (
            <Button onClick={() => void reconnect()} variant="secondary">
              {t("Reauthorize and reconnect")}
            </Button>
          ) : null}
          {isLive ? (
            <Button onClick={() => void stop()} variant="neutral-outline">
              {t("Stop camera")}
            </Button>
          ) : null}
        </div>
      </div>
    </Surface>
  );
}

function CleanupStatus({ report }: { readonly report: MediaCleanupReport }) {
  const { t } = useI18n();
  return (
    <Alert title={t("Cleanup results")} variant="info">
      {t(
        "Camera stopped: {local}. Connection closed: {sdk}. Access revocation: {backend}.",
        {
          local: report.localTrack,
          sdk: report.sdkDisconnect,
          backend: report.backendRevoke,
        },
      )}
    </Alert>
  );
}

function CameraStatus({ state }: { readonly state: string }) {
  const { t } = useI18n();
  const copy: Record<
    string,
    {
      title: string;
      text: string;
      variant: "info" | "success" | "warning" | "danger";
    }
  > = {
    idle: {
      title: t("Camera is off"),
      text: t("Nothing is captured until you choose to request camera access."),
      variant: "info",
    },
    "requesting-permission": {
      title: t("Waiting for browser permission"),
      text: t("Use the browser prompt to allow or deny this camera request."),
      variant: "info",
    },
    connecting: {
      title: t("Connecting"),
      text: t("Connecting. The camera is not live yet."),
      variant: "info",
    },
    live: {
      title: t("Camera is live"),
      text: t("Your camera is being shared. Your microphone is never shared."),
      variant: "success",
    },
    reconnecting: {
      title: t("Fresh authorization required"),
      text: t(
        "The connection changed. Quizopia is cleaning up before requesting fresh access.",
      ),
      variant: "warning",
    },
    "permission-denied": {
      title: t("Camera permission denied"),
      text: t(
        "No camera is active. Refusing the camera does not automatically affect your result.",
      ),
      variant: "warning",
    },
    "device-missing": {
      title: t("No camera found"),
      text: t(
        "No camera is active. Connect a camera or contact your Teacher about accommodations.",
      ),
      variant: "warning",
    },
    "device-error": {
      title: t("Camera stopped or failed"),
      text: t(
        "The camera could not provide video. No automatic penalty was applied.",
      ),
      variant: "danger",
    },
    unavailable: {
      title: t("Monitoring media unavailable"),
      text: t(
        "Camera access is temporarily unavailable. The camera is not active.",
      ),
      variant: "warning",
    },
    disconnected: {
      title: t("Camera disconnected"),
      text: t(
        "Cleanup finished — see details below. Reconnect requires fresh permission.",
      ),
      variant: "warning",
    },
    stopping: {
      title: t("Stopping camera"),
      text: t(
        "The camera, its connection, and its access grant are being cleaned up.",
      ),
      variant: "info",
    },
  };
  const current = copy[state] ?? copy.unavailable!;
  return (
    <Alert title={current.title} variant={current.variant}>
      {current.text}
    </Alert>
  );
}
