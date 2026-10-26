"use client";
import Link from "next/link";
import { useMemo } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { useI18n } from "../../../lib/i18n/provider";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import { createQuizApiClient } from "../../quizzes/api/quiz-api-client";
import { usePublicationClient } from "../hooks/use-publication-client";
import { PublicationDirectory } from "./publication-directory";
import { PublicationCreateScreen } from "./publication-create-screen";
import { PublicationDetailScreen } from "./publication-detail-screen";
import type { PublicationSource } from "./publication-source";
export type { PublicationSource } from "./publication-source";
export { PublicationCreateScreen } from "./publication-create-screen";
export { PublicationDetailScreen } from "./publication-detail-screen";

export function PublicationPage({
  source,
  publicationId,
}: {
  readonly source?: PublicationSource | null;
  readonly publicationId?: string;
}) {
  return (
    <AuthenticatedBoundary>
      <TeacherPublicationPage publicationId={publicationId} source={source} />
    </AuthenticatedBoundary>
  );
}

function TeacherPublicationPage({
  source,
  publicationId,
}: {
  readonly source?: PublicationSource | null;
  readonly publicationId?: string;
}) {
  const {
    user,
    activeWorkspace,
    switchWorkspace,
    authenticatedRequests,
    getSessionGeneration,
  } = useAuth();
  const { t } = useI18n();
  const client = usePublicationClient();
  const quizClient = useMemo(
    () =>
      authenticatedRequests
        ? createQuizApiClient({ authenticatedRequests })
        : null,
    [authenticatedRequests],
  );
  const scope = `${user?.id}:${getSessionGeneration()}:${publicationId ?? `${source?.quizId}:${source?.quizVersionId}:${source?.versionNumber}`}`;
  return (
    <ApplicationShell currentSection="assessments">
      {!user?.roles.includes("TEACHER") ? (
        <Alert title={t("Teacher access required")} variant="warning">
          {t(
            "Current Teacher authority and ownership are required. Workspace selection does not grant a role.",
          )}{" "}
          <Link className="font-semibold underline" href="/app">
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
            {t(
              "Use your existing Teacher role to manage Publications. The server checks authority for every operation.",
            )}
          </p>
          <Button className="mt-5" onClick={() => switchWorkspace("TEACHING")}>
            {t("Switch to Teaching workspace")}
          </Button>
        </Surface>
      ) : client === null || quizClient === null ? (
        <Alert title={t("Publication access unavailable")} variant="warning">
          {t(
            "Your authenticated request service is unavailable. Sign in again before continuing.",
          )}
        </Alert>
      ) : publicationId !== undefined ? (
        <PublicationDetailScreen
          client={client}
          key={scope}
          publicationId={publicationId}
        />
      ) : source ? (
        <PublicationCreateScreen
          client={client}
          key={scope}
          quizClient={quizClient}
          source={source}
        />
      ) : (
        <PublicationDirectory client={client} key={scope} />
      )}
    </ApplicationShell>
  );
}
