"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { MediaCleanupReport, MediaGrant } from "../model/media";
import type { MonitoringSession } from "../model/monitoring";
import {
  liveKitBrowserAdapter,
  type LiveKitBrowserAdapter,
  type TeacherMediaConnection,
} from "../media/livekit-browser";
import { useProctoringClients } from "./use-proctoring-clients";

export type TeacherCameraState =
  | "idle"
  | "authorizing"
  | "connected-no-camera"
  | "live"
  | "stopping"
  | "reconnecting"
  | "disconnected"
  | "unavailable";

export function useTeacherCamera(options: {
  readonly session: MonitoringSession;
  readonly adapter?: LiveKitBrowserAdapter;
}) {
  const { session, adapter = liveKitBrowserAdapter } = options;
  const clients = useProctoringClients();
  const [state, setState] = useState<TeacherCameraState>("idle");
  const [cleanupReport, setCleanupReport] = useState<MediaCleanupReport | null>(
    null,
  );
  const video = useRef<HTMLVideoElement | null>(null);
  const connection = useRef<TeacherMediaConnection | null>(null);
  const grant = useRef<MediaGrant | null>(null);
  const operation = useRef(0);
  const renewalTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const teardown = useRef<Promise<void>>(Promise.resolve());
  const mounted = useRef(true);

  const cancelRenewal = () => {
    if (renewalTimer.current !== null) clearTimeout(renewalTimer.current);
    renewalTimer.current = null;
  };

  const current = useCallback(
    (ticket: number, generation: number) =>
      mounted.current &&
      operation.current === ticket &&
      clients?.getSessionGeneration() === generation,
    [clients],
  );

  const queueTeardown = useCallback(
    (
      ownedConnection: TeacherMediaConnection | null,
      ownedGrant: MediaGrant | null,
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
          clients === null || ownedGrant === null
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
          setCleanupReport({
            localTrack: "not-applicable",
            sdkDisconnect,
            backendRevoke,
          });
      });
      teardown.current = pending;
      return pending;
    },
    [clients, session.id],
  );

  const cleanup = useCallback(
    async (invalidate = true) => {
      if (invalidate) operation.current += 1;
      cancelRenewal();
      const ownedConnection = connection.current;
      const ownedGrant = grant.current;
      connection.current = null;
      grant.current = null;
      const pending = queueTeardown(
        ownedConnection,
        ownedGrant,
        operation.current,
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
      ownedConnection: TeacherMediaConnection,
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
      setState(signal);
      void queueTeardown(ownedConnection, ownedGrant, lossTicket);
    },
    [current, queueTeardown],
  );

  const scheduleRenewal = useCallback(
    function schedule(
      activeGrant: MediaGrant,
      ticket: number,
      generation: number,
    ) {
      cancelRenewal();
      renewalTimer.current = setTimeout(
        async () => {
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
          if (!result.ok || result.value.role !== "TEACHER") {
            await cleanup();
            if (mounted.current) setState("unavailable");
            return;
          }
          grant.current = result.value;
          schedule(result.value, ticket, generation);
        },
        Math.min(
          2_147_000_000,
          Math.max(
            250,
            Date.parse(activeGrant.leaseUntil) - Date.now() - 2_000,
          ),
        ),
      );
    },
    [cleanup, clients, current, revokeStale, session.id],
  );

  const connect = useCallback(async () => {
    if (
      clients === null ||
      video.current === null ||
      session.status !== "ACTIVE"
    ) {
      setState("unavailable");
      return;
    }
    const ticket = operation.current + 1;
    operation.current = ticket;
    const generation = clients.generation;
    await cleanup(false);
    await teardown.current;
    if (!current(ticket, generation) || video.current === null) return;
    setState("authorizing");
    const grantResult = await clients.media.createGrant(session.id);
    if (!current(ticket, generation)) {
      if (grantResult.ok) await revokeStale(grantResult.value);
      return;
    }
    if (!grantResult.ok) {
      setState("unavailable");
      return;
    }
    if (grantResult.value.role !== "TEACHER") {
      await revokeStale(grantResult.value);
      setState("unavailable");
      return;
    }
    grant.current = grantResult.value;
    const nextConnection = adapter.createTeacherConnection(
      (signal) => {
        if (signal === "reconnecting" || signal === "disconnected") {
          loseConnection(nextConnection, signal, ticket, generation);
        } else if (
          signal === "connected" &&
          current(ticket, generation) &&
          connection.current === nextConnection &&
          !nextConnection.hasCamera()
        ) {
          setState("connected-no-camera");
        }
      },
      (available) => {
        if (
          current(ticket, generation) &&
          connection.current === nextConnection
        )
          setState(available ? "live" : "connected-no-camera");
      },
    );
    connection.current = nextConnection;
    try {
      await nextConnection.connect(grantResult.value, video.current);
      if (
        !current(ticket, generation) ||
        connection.current !== nextConnection
      ) {
        await nextConnection.disconnect();
        return;
      }
      if (!nextConnection.hasCamera()) setState("connected-no-camera");
      scheduleRenewal(grantResult.value, ticket, generation);
    } catch {
      await cleanup();
      if (mounted.current) setState("unavailable");
    }
  }, [
    adapter,
    cleanup,
    clients,
    current,
    loseConnection,
    revokeStale,
    scheduleRenewal,
    session.id,
    session.status,
  ]);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      void cleanup();
    };
  }, [cleanup]);

  return {
    state,
    cleanupReport,
    videoRef(element: HTMLVideoElement | null) {
      video.current = element;
    },
    connect,
    reconnect: connect,
    async stop() {
      const ticket = operation.current + 1;
      const generation = clients?.generation ?? null;
      setState("stopping");
      await cleanup();
      if (
        mounted.current &&
        operation.current === ticket &&
        clients?.getSessionGeneration() === generation
      )
        setState("disconnected");
    },
  };
}
