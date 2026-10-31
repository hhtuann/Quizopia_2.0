"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button, OUTLINE_LINK_CLASSES } from "../../../components/ui/button";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { StateBadge } from "../../../components/ui/state-badge";
import { Surface } from "../../../components/ui/surface";
import { useI18n } from "../../../lib/i18n/provider";
import { publicationFailureMessage } from "../../assessments/api/publication-api-client";
import type { OwnerView } from "../../assessments/api/publication-contract.generated";
import { usePublicationClient } from "../../assessments/hooks/use-publication-client";
import { publicationStateVariants } from "../../assessments/model/publication";
import { RealtimeStatusInline } from "../../realtime/components/realtime-status";
import type { RealtimeResource } from "../../realtime/model/realtime-frame";
import {
  useRealtimeResync,
  useRealtimeSubscriptions,
} from "../../realtime/hooks/use-realtime-subscriptions";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import { StudentResultsDashboardSection } from "./student-results-dashboard";
import { useTeacherDashboardClient } from "../hooks/use-teacher-dashboard-client";

export function TeacherResultsPage({
  publicationId,
}: {
  readonly publicationId: string;
}) {
  return (
    <AuthenticatedBoundary>
      <TeacherDashboardRouterPage publicationId={publicationId} />
    </AuthenticatedBoundary>
  );
}

function TeacherDashboardRouterPage({
  publicationId,
}: {
  readonly publicationId: string;
}) {
  const { user, activeWorkspace, switchWorkspace, getSessionGeneration } =
    useAuth();
  const { t } = useI18n();
  const client = useTeacherDashboardClient();
  const scope = `${user?.id}:${getSessionGeneration()}:${publicationId}`;
  return (
    <ApplicationShell currentSection="assessments">
      <div className="mb-6">
        <Link
          className={OUTLINE_LINK_CLASSES}
          href={`/app/assessments/${publicationId}`}
        >
          {t("Back to Publication details")}
        </Link>
      </div>
      {!user?.roles.includes("TEACHER") ? (
        <Alert title={t("Teacher access required")} variant="warning">
          {t("You need the Teacher role and ownership of this assessment.")}{" "}
          <Link className="rounded py-2 font-semibold underline" href="/app">
            {t("Return to application")}
          </Link>
          .
        </Alert>
      ) : activeWorkspace !== "TEACHING" ? (
        <Surface className="p-6">
          <h1 className="text-2xl font-bold">
            {t("Open the Teaching workspace")}
          </h1>
          <p className="mt-3 text-foreground-secondary">
            {t("Teacher results live in the Teaching workspace.")}
          </p>
          <Button className="mt-5" onClick={() => switchWorkspace("TEACHING")}>
            {t("Switch to Teaching workspace")}
          </Button>
        </Surface>
      ) : client === null ? (
        <Alert title={t("Results unavailable")} variant="warning">
          {t("Your session has ended. Please sign in again.")}
        </Alert>
      ) : (
        <StandaloneDashboardScreen
          client={client}
          key={scope}
          publicationId={publicationId}
        />
      )}
    </ApplicationShell>
  );
}

/**
 * Standalone route wrapper for the one unified student dashboard. It reads the
 * authoritative Publication for the header and owns the single realtime
 * subscription while OPEN, forwarding hints into the dashboard's REST
 * refresh. The same dashboard section is embedded on the Publication detail
 * page; this route exists for deep links from reviews and notifications.
 */
function StandaloneDashboardScreen({
  client,
  publicationId,
}: {
  readonly client: ReturnType<typeof useTeacherDashboardClient>;
  readonly publicationId: string;
}) {
  const { t, localize } = useI18n();
  const publicationClient = usePublicationClient();
  const lifetime = useRef<AbortController | null>(null);
  const readController = useRef<AbortController | null>(null);
  const readSequence = useRef(0);
  const [publication, setPublication] = useState<OwnerView | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<string | null>(null);
  const dashboardInvalidate = useRef<(() => void) | null>(null);

  const refresh = useCallback((): Promise<OwnerView | null> => {
    if (publicationClient === null) return Promise.resolve(null);
    const sequence = ++readSequence.current;
    readController.current?.abort();
    const controller = new AbortController();
    readController.current = controller;
    return publicationClient
      .get(publicationId, controller.signal)
      .then((result) => {
        if (
          controller.signal.aborted ||
          lifetime.current?.signal.aborted ||
          sequence !== readSequence.current
        )
          return null;
        setLoading(false);
        if (!result.ok) {
          setPublication(null);
          setFailure(publicationFailureMessage(result.error));
          return null;
        }
        setFailure(null);
        setPublication(result.value);
        return result.value;
      });
  }, [publicationClient, publicationId]);

  useEffect(() => {
    const scope = new AbortController();
    lifetime.current = scope;
    void refresh();
    return () => {
      scope.abort();
      readController.current?.abort();
    };
  }, [refresh]);

  // WebSocket hints stay invalidation-only; the authoritative Publication and
  // dashboard aggregates re-read through REST with stale-response fencing.
  const publicationState = publication?.state;
  const realtimeResources = useMemo<readonly RealtimeResource[]>(
    () =>
      publicationState === "OPEN"
        ? [{ kind: "PUBLICATION_TEACHER" as const, id: publicationId }]
        : [],
    [publicationState, publicationId],
  );
  const realtimeResync = useRealtimeResync({ active: true, run: refresh });
  const realtimeStatus = useRealtimeSubscriptions({
    enabled: true,
    resources: realtimeResources,
    onSignal: () => {
      realtimeResync.request();
      dashboardInvalidate.current?.();
    },
  });

  const registerInvalidate = useCallback((invalidate: () => void) => {
    dashboardInvalidate.current = invalidate;
  }, []);

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-primary">
            {t("Publication results")}
          </p>
          <h1 className="mt-1 break-words font-heading text-3xl font-bold">
            {publication === null
              ? t("Student results")
              : publication.configuration.title}
          </h1>
          {publication !== null ? (
            <p className="mt-2 flex flex-wrap items-center gap-3 text-sm">
              {/* Canonical API state enum rendered verbatim by policy. */}
              <StateBadge variant={publicationStateVariants[publication.state]}>
                {publication.state}
              </StateBadge>
              <span className="text-foreground-muted">
                {t("Version {number}", {
                  number: publication.configuration.versionNumber,
                })}
              </span>
            </p>
          ) : null}
          <div className="mt-2">
            <RealtimeStatusInline status={realtimeStatus} />
          </div>
        </div>
      </header>
      {failure !== null ? (
        <Alert title={t("Publication could not be loaded")} variant="warning">
          {localize(failure)}{" "}
          <Button
            className="mt-3"
            disabled={loading}
            onClick={() => {
              setLoading(true);
              void refresh();
            }}
            variant="secondary"
          >
            {t("Retry Publication details")}
          </Button>
        </Alert>
      ) : null}
      {loading && publication === null ? (
        <Surface className="p-6">
          <div className="flex items-center gap-3" role="status">
            <LoadingIndicator label={t("Loading results")} />
            {t("Loading results…")}
          </div>
        </Surface>
      ) : (
        <StudentResultsDashboardSection
          client={client!}
          onRegisterInvalidate={registerInvalidate}
          publicationId={publicationId}
        />
      )}
    </div>
  );
}
