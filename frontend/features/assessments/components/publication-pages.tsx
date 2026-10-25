"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMemo, useState, type FormEvent } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button, PRIMARY_LINK_CLASSES } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
import { useAuth } from "../../auth/auth-provider";
import { AuthenticatedBoundary } from "../../auth/components/authenticated-boundary";
import { createQuizApiClient } from "../../quizzes/api/quiz-api-client";
import { usePublicationClient } from "../hooks/use-publication-client";
import { canonicalUuid } from "../model/publication";
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
        <Alert title="Teacher access required" variant="warning">
          Current Teacher authority and ownership are required. Workspace
          selection does not grant a role.{" "}
          <Link className="font-semibold underline" href="/app">
            Return to application
          </Link>
          .
        </Alert>
      ) : activeWorkspace !== "TEACHING" ? (
        <Surface className="p-6">
          <h1 className="text-2xl font-bold">Open the Teaching workspace</h1>
          <p className="mt-3 text-foreground-secondary">
            Use your existing Teacher role to manage Publications. The server
            checks authority for every operation.
          </p>
          <Button className="mt-5" onClick={() => switchWorkspace("TEACHING")}>
            Switch to Teaching workspace
          </Button>
        </Surface>
      ) : client === null || quizClient === null ? (
        <Alert title="Publication access unavailable" variant="warning">
          Your authenticated request service is unavailable. Sign in again
          before continuing.
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
        <PublicationEntry />
      )}
    </ApplicationShell>
  );
}

function PublicationEntry() {
  const router = useRouter();
  const [id, setId] = useState("");
  const [invalid, setInvalid] = useState(false);
  function lookup(event: FormEvent) {
    event.preventDefault();
    if (!canonicalUuid.safeParse(id).success) {
      setInvalid(true);
      return;
    }
    router.push(`/app/assessments/${id}`);
  }
  return (
    <div className="mx-auto max-w-4xl space-y-5">
      <h1 className="font-heading text-3xl font-bold">
        Assessment Publications
      </h1>
      <Surface className="p-6">
        <h2 className="text-xl font-bold">
          Start with an immutable QuizVersion
        </h2>
        <p className="mt-3 leading-7 text-foreground-secondary">
          Open an owned Quiz in the Quiz Library, choose Published versions,
          then select the exact version and Create assessment. Publications do
          not use the mutable Quiz Draft.
        </p>
        <Link className={`${PRIMARY_LINK_CLASSES} mt-5`} href="/app/quizzes">
          Choose a published QuizVersion
        </Link>
      </Surface>
      <Surface className="p-6">
        <h2 className="text-xl font-bold">Open a known Publication</h2>
        <p className="mt-2 text-sm text-foreground-muted">
          An owner-scoped Publication directory is not available. Enter a known
          ID; the server verifies ownership.
        </p>
        <form className="mt-4 space-y-4" noValidate onSubmit={lookup}>
          <TextField
            error={
              invalid
                ? "Enter a canonical lowercase Publication UUID."
                : undefined
            }
            label="Publication ID"
            onChange={(event) => setId(event.target.value)}
            required
            value={id}
          />
          <Button type="submit" variant="secondary">
            Open Publication
          </Button>
        </form>
      </Surface>
    </div>
  );
}
