"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { ActivityEvidenceStream } from "../activity/activity-evidence-stream";
import type { MediaCleanupReport, MediaGrant } from "../model/media";
import type { MonitoringSession } from "../model/monitoring";
import {
  classifyCameraFailure,
  liveKitBrowserAdapter,
  type BrowserCameraTrack,
  type LiveKitBrowserAdapter,
  type StudentMediaConnection,
} from "../media/livekit-browser";
import { useProctoringClients } from "./use-proctoring-clients";

export type StudentCameraState =
  | "idle"
  | "requesting-permission"
  | "connecting"
  | "live"
  | "reconnecting"
  | "permission-denied"
  | "device-missing"
  | "device-error"
  | "unavailable"
  | "disconnected"
  | "stopping";

export interface StudentCameraController {
  readonly state: StudentCameraState;
  readonly cleanupReport: MediaCleanupReport | null;
  readonly previewRef: (element: HTMLVideoElement | null) => void;
  start(): Promise<void>;
  reconnect(): Promise<void>;
  stop(): Promise<void>;
}

export function useStudentCamera(options: {
  readonly session: MonitoringSession;
  readonly activity: ActivityEvidenceStream;
  readonly adapter?: LiveKitBrowserAdapter;
}): StudentCameraController {
  const { session, activity, adapter = liveKitBrowserAdapter } = options;
  const clients = useProctoringClients();
  const [state, setState] = useState<StudentCameraState>("idle");
  const [cleanupReport, setCleanupReport] = useState<MediaCleanupReport | null>(
    null,
  );
  const previewElement = useRef<HTMLVideoElement | null>(null);
  const track = useRef<BrowserCameraTrack | null>(null);
  const connection = useRef<StudentMediaConnection | null>(null);
  const grant = useRef<MediaGrant | null>(null);
  const renewalTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const teardown = useRef<Promise<void>>(Promise.resolve());
  const operation = useRef(0);
  const mounted = useRef(true);

  const current = useCallback(
    (ticket: number, generation: number) =>
      mounted.current &&
      ticket === operation.current &&
      clients?.getSessionGeneration() === generation,
    [clients],
  );

  const cancelRenewal = () => {
    if (renewalTimer.current !== null) clearTimeout(renewalTimer.current);
    renewalTimer.current = null;
  };

  const queueTeardown = useCallback(
    (
      ownedConnection: StudentMediaConnection | null,
      ownedGrant: MediaGrant | null,
      revoke: boolean,
      localTrack: MediaCleanupReport["localTrack"],
      ticket: number,
    ) => {
      const previous = teardown.current;
      const pending = previous.then(async () => {
        const [sdkDisconnect, backendRevoke] = await Promise.all([
          ownedConnection === null
            ? Promise.resolve("not-present" as const)
            : ownedConnection
                .disconnect()
                .then(() => "succeeded" as const)
                .catch(() => "failed" as const),
          !revoke || ownedGrant === null || clients === null
            ? Promise.resolve("not-requested" as const)
            : clients.media
                .revokeGrant(session.id, ownedGrant.id)
                .then((result) =>
                  !result.ok
                    ? ("failed" as const)
                    : result.value.status === "REVOKED"
                      ? ("revoked" as const)
                      : ("revoke-pending" as const),
                )
                .catch(() => "failed" as const),
        ]);
        if (mounted.current && operation.current === ticket)
          setCleanupReport({ localTrack, sdkDisconnect, backendRevoke });
      });
      teardown.current = pending;
      return pending;
    },
    [clients, session.id],
  );

  const cleanup = useCallback(
    async (revoke: boolean, invalidate = true, keepTrack = false) => {
      if (invalidate) operation.current += 1;
      cancelRenewal();
      const ownedConnection = connection.current;
      const ownedTrack = track.current;
      const ownedGrant = grant.current;
      connection.current = null;
      grant.current = null;
      if (!keepTrack) track.current = null;
      let localTrack: MediaCleanupReport["localTrack"] =
        ownedTrack === null
          ? "not-present"
          : keepTrack
            ? "retained-for-reauthorization"
            : "stopped";
      if (!keepTrack) {
        try {
          ownedTrack?.detach();
        } catch {
          /* best-effort local cleanup */
        }
        try {
          ownedTrack?.stop();
        } catch {
          localTrack = "failed";
        }
      }
      const ticket = operation.current;
      const pending = queueTeardown(
        ownedConnection,
        ownedGrant,
        revoke,
        localTrack,
        ticket,
      );
      await pending;
    },
    [queueTeardown],
  );

  const revokeStale = useCallback(
    async (staleGrant: MediaGrant) => {
      if (clients !== null)
        await clients.media.revokeGrant(session.id, staleGrant.id);
    },
    [clients, session.id],
  );

  const loseConnection = useCallback(
    (
      ownedConnection: StudentMediaConnection,
      signal: "reconnecting" | "disconnected",
      ticket: number,
      generation: number,
    ) => {
      if (
        !current(ticket, generation) ||
        connection.current !== ownedConnection
      )
        return;
      operation.current += 1;
      const lossTicket = operation.current;
      cancelRenewal();
      const ownedGrant = grant.current;
      connection.current = null;
      grant.current = null;
      activity.observe({
        type: signal === "reconnecting" ? "RECONNECTING" : "MEDIA_DISCONNECTED",
      });
      setState(signal);
      void queueTeardown(
        ownedConnection,
        ownedGrant,
        true,
        track.current === null ? "not-present" : "retained-for-reauthorization",
        lossTicket,
      );
    },
    [activity, current, queueTeardown],
  );

  const scheduleRenewal = useCallback(
    function schedule(
      activeGrant: MediaGrant,
      ticket: number,
      generation: number,
    ) {
      cancelRenewal();
      const delay = Math.min(
        2_147_000_000,
        Math.max(250, Date.parse(activeGrant.leaseUntil) - Date.now() - 2_000),
      );
      renewalTimer.current = setTimeout(async () => {
        if (
          !current(ticket, generation) ||
          clients === null ||
          connection.current === null
        )
          return;
        const result = await clients.media.renewGrant(
          session.id,
          activeGrant.id,
        );
        if (!current(ticket, generation) || connection.current === null) {
          if (result.ok) await revokeStale(result.value);
          return;
        }
        if (!result.ok || result.value.role !== "STUDENT") {
          activity.observe({ type: "MEDIA_DISCONNECTED" });
          await cleanup(true);
          if (mounted.current) setState("unavailable");
          return;
        }
        grant.current = result.value;
        schedule(result.value, ticket, generation);
      }, delay);
    },
    [activity, cleanup, clients, current, revokeStale, session.id],
  );

  const connect = useCallback(
    async (
      activeGrant: MediaGrant,
      activeTrack: BrowserCameraTrack,
      ticket: number,
      generation: number,
    ) => {
      const nextConnection = adapter.createStudentConnection((signal) => {
        if (signal === "reconnecting" || signal === "disconnected")
          loseConnection(nextConnection, signal, ticket, generation);
      });
      connection.current = nextConnection;
      await nextConnection.connect(activeGrant, activeTrack);
      if (
        !current(ticket, generation) ||
        connection.current !== nextConnection
      ) {
        await nextConnection.disconnect();
        return false;
      }
      setState("live");
      scheduleRenewal(activeGrant, ticket, generation);
      return true;
    },
    [adapter, current, loseConnection, scheduleRenewal],
  );

  const start = useCallback(async () => {
    if (clients === null || session.status !== "ACTIVE") {
      setState("unavailable");
      return;
    }
    const ticket = operation.current + 1;
    operation.current = ticket;
    const generation = clients.generation;
    await cleanup(true, false);
    if (!current(ticket, generation)) return;
    setState("requesting-permission");
    let nextTrack: BrowserCameraTrack;
    try {
      nextTrack = await adapter.createCameraTrack();
    } catch (error) {
      if (!current(ticket, generation)) return;
      const failure = classifyCameraFailure(error);
      activity.observe({
        type:
          failure === "permission-denied"
            ? "CAMERA_PERMISSION_DENIED"
            : failure === "device-missing"
              ? "DEVICE_MISSING"
              : "DEVICE_ERROR",
      });
      setState(failure);
      return;
    }
    if (!current(ticket, generation)) {
      nextTrack.stop();
      return;
    }
    track.current = nextTrack;
    if (previewElement.current !== null)
      nextTrack.attach(previewElement.current);
    activity.observe({ type: "CAMERA_PERMISSION_GRANTED" });
    setState("connecting");
    const grantResult = await clients.media.createGrant(session.id);
    if (!current(ticket, generation)) {
      if (grantResult.ok) await revokeStale(grantResult.value);
      nextTrack.stop();
      return;
    }
    if (!grantResult.ok) {
      await cleanup(false);
      if (mounted.current) setState("unavailable");
      return;
    }
    if (grantResult.value.role !== "STUDENT") {
      await revokeStale(grantResult.value);
      await cleanup(false);
      if (mounted.current) setState("unavailable");
      return;
    }
    grant.current = grantResult.value;
    try {
      if (!(await connect(grantResult.value, nextTrack, ticket, generation)))
        return;
      void nextTrack.ended.then(async () => {
        if (!current(ticket, generation)) return;
        activity.observe({ type: "CAMERA_UNAVAILABLE" });
        await cleanup(true);
        if (mounted.current) setState("device-error");
      });
    } catch {
      if (current(ticket, generation))
        activity.observe({ type: "MEDIA_DISCONNECTED" });
      await cleanup(true);
      if (mounted.current) setState("unavailable");
    }
  }, [
    activity,
    adapter,
    cleanup,
    clients,
    connect,
    current,
    revokeStale,
    session.id,
    session.status,
  ]);

  const reconnect = useCallback(async () => {
    if (clients === null || track.current === null) {
      await start();
      return;
    }
    const ticket = operation.current + 1;
    operation.current = ticket;
    const generation = clients.generation;
    cancelRenewal();
    await teardown.current;
    if (!current(ticket, generation) || track.current === null) return;
    setState("reconnecting");
    const result = await clients.media.createGrant(session.id);
    if (!current(ticket, generation)) {
      if (result.ok) await revokeStale(result.value);
      return;
    }
    if (!result.ok || result.value.role !== "STUDENT") {
      if (result.ok) await revokeStale(result.value);
      await cleanup(false);
      if (mounted.current) setState("unavailable");
      return;
    }
    grant.current = result.value;
    try {
      await connect(result.value, track.current, ticket, generation);
    } catch {
      await cleanup(true);
      if (mounted.current) setState("unavailable");
    }
  }, [cleanup, clients, connect, current, revokeStale, session.id, start]);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      void cleanup(true);
    };
  }, [cleanup]);

  return {
    state,
    cleanupReport,
    previewRef(element) {
      previewElement.current = element;
      if (element !== null) track.current?.attach(element);
    },
    start,
    reconnect,
    async stop() {
      const ticket = operation.current + 1;
      const generation = clients?.generation ?? null;
      setState("stopping");
      await cleanup(true);
      if (
        mounted.current &&
        operation.current === ticket &&
        clients?.getSessionGeneration() === generation
      )
        setState("disconnected");
    },
  };
}
