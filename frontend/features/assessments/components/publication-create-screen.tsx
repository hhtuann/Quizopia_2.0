"use client";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { Surface } from "../../../components/ui/surface";
import { useI18n } from "../../../lib/i18n/provider";
import type { QuizApiClient } from "../../quizzes/api/quiz-api-client";
import {
  isUncertain,
  publicationFailureMessage,
  PUBLICATION_REQUEST_TIMEOUT,
  type PublicationApiClient,
  type PublicationFailure,
} from "../api/publication-api-client";
import type { Configuration } from "../api/publication-contract.generated";
import { newIdempotencyKey, sameConfiguration } from "../model/publication";
import { PublicationConfigurationForm } from "./publication-configuration-form";
import type { PublicationSource } from "./publication-source";

export function PublicationCreateScreen({
  client,
  quizClient,
  source,
}: {
  readonly client: PublicationApiClient;
  readonly quizClient: QuizApiClient;
  readonly source: PublicationSource;
}) {
  const { t, localize } = useI18n();
  const router = useRouter();
  const lifetime = useRef<AbortController | null>(null);
  const inFlight = useRef(false);
  const receipt = useRef<{
    readonly configuration: Configuration;
    readonly key: string;
  } | null>(null);
  const [retry, setRetry] = useState(0);
  const [initial, setInitial] = useState<Configuration | null>(null);
  const [sourceError, setSourceError] = useState(false);
  const [failure, setFailure] = useState<PublicationFailure | null>(null);
  const [pending, setPending] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [created, setCreated] = useState<{
    readonly id: string;
    readonly state: string;
  } | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const scope = new AbortController();
    lifetime.current = scope;
    return () => scope.abort();
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    const timeout = setTimeout(() => {
      controller.abort();
      setSourceError(true);
    }, PUBLICATION_REQUEST_TIMEOUT);
    void quizClient
      .getPublishedVersion(
        source.quizId,
        source.versionNumber,
        controller.signal,
      )
      .then((result) => {
        if (controller.signal.aborted) return;
        clearTimeout(timeout);
        if (
          !result.ok ||
          result.value.id !== source.quizVersionId ||
          result.value.quizId !== source.quizId ||
          result.value.versionNumber !== source.versionNumber
        ) {
          setSourceError(true);
          return;
        }
        const version = result.value;
        // Retain metadata only; answer-bearing version content never enters Publication state.
        setInitial({
          title: version.titleSnapshot ?? "",
          description: version.descriptionSnapshot,
          quizId: source.quizId,
          quizVersionId: source.quizVersionId,
          versionNumber: source.versionNumber,
          audience: "CLASS",
          classroomId: null,
          startAt: "",
          endAt: "",
          durationMinutes: 30,
          monitoringChoice: "UNCONFIGURED",
          maxAttempts: 1,
        });
        setSourceError(false);
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          clearTimeout(timeout);
          setSourceError(true);
        }
      });
    return () => {
      clearTimeout(timeout);
      controller.abort();
    };
  }, [
    quizClient,
    retry,
    source.quizId,
    source.quizVersionId,
    source.versionNumber,
  ]);

  async function create(configuration: Configuration) {
    if (inFlight.current || lifetime.current?.signal.aborted) return;
    inFlight.current = true;
    setPending(true);
    setFailure(null);
    setAttempt((value) => value + 1);
    if (
      receipt.current === null ||
      !sameConfiguration(receipt.current.configuration, configuration)
    )
      receipt.current = {
        configuration: structuredClone(configuration),
        key: newIdempotencyKey(),
      };
    const command = receipt.current;
    const result = await client.create(
      command.configuration,
      command.key,
      lifetime.current?.signal,
    );
    if (lifetime.current?.signal.aborted) return;
    if (result.ok) {
      setCreated({ id: result.value.publicationId, state: result.value.state });
      setUncertain(false);
      setPending(false);
      router.push(`/app/assessments/${result.value.publicationId}`);
      return;
    }
    setFailure(result.error);
    // A later rejected replay cannot disprove an earlier committed creation.
    setUncertain((current) => current || isUncertain(result.error));
    setPending(false);
    inFlight.current = false;
  }

  return (
    <div className="space-y-5">
      <header>
        <h1 className="font-heading text-2xl font-bold sm:text-3xl">
          {t("Create Assessment Publication")}
        </h1>
        <p className="mt-2 text-foreground-secondary">
          {t("Configure a DRAFT from the selected version. OPEN comes later.")}
        </p>
      </header>
      {sourceError ? (
        <Surface className="space-y-4 p-5">
          <Alert
            title={t("Selected QuizVersion could not be verified")}
            variant="danger"
          >
            {t(
              "The version could not be reached. Your selection was not changed.",
            )}
          </Alert>
          <Button
            onClick={() => {
              setSourceError(false);
              setRetry((value) => value + 1);
            }}
            variant="secondary"
          >
            {t("Retry selected version")}
          </Button>
        </Surface>
      ) : initial === null ? (
        <div className="flex items-center gap-3 p-6" role="status">
          <LoadingIndicator label={t("Verifying selected QuizVersion")} />
          {t("Verifying selected QuizVersion…")}
        </div>
      ) : (
        <>
          {created ? (
            <Alert title={t("Publication confirmed")} variant="success">
              {t("Created as {state}.", { state: created.state })}{" "}
              <Link
                className="font-semibold underline"
                href={`/app/assessments/${created.id}`}
              >
                {t("Open the assessment")}
              </Link>
              .
            </Alert>
          ) : null}
          {failure ? (
            <Alert
              key={attempt}
              title={
                uncertain
                  ? t("Creation outcome is uncertain")
                  : t("DRAFT was not confirmed")
              }
              variant="warning"
            >
              {localize(publicationFailureMessage(failure))}
              {uncertain ? (
                <p className="mt-2">
                  {t(
                    "This creation may have gone through. Retry the exact request below; settings stay locked. Leaving this page loses the safe retry key.",
                  )}
                </p>
              ) : null}
            </Alert>
          ) : null}
          {uncertain ? (
            <Button
              aria-label={pending ? t("Retrying original creation") : undefined}
              isLoading={pending}
              loadingLabel={t("Retrying original creation")}
              onClick={() => {
                if (receipt.current) void create(receipt.current.configuration);
              }}
            >
              {t("Retry same creation")}
            </Button>
          ) : null}
          <PublicationConfigurationForm
            disabled={uncertain || created !== null}
            initial={initial}
            isPending={pending}
            mode="create"
            onSubmit={(configuration) => void create(configuration)}
          />
        </>
      )}
    </div>
  );
}
