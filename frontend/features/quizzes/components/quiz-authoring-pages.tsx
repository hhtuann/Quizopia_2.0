"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { Surface } from "../../../components/ui/surface";
import { useAuth } from "../../auth/auth-provider";
import {
  createQuizApiClient,
  type QuizApiClient,
  type QuizApiFailure,
  type QuizDraftInput,
  type QuizLibraryItem,
  type QuizMarkdownServerError,
} from "../api/quiz-api-client";
import {
  QuizMarkdownEditor,
  type QuizMarkdownEditorHandle,
} from "./quiz-markdown-editor";
import { QuizPreview } from "./quiz-preview";

const linkButtonClasses =
  "inline-flex min-h-11 items-center justify-center rounded-lg border border-primary bg-primary px-4 py-2.5 text-sm font-semibold text-foreground-inverse shadow-primary transition-colors duration-200 hover:border-primary-hover hover:bg-primary-hover focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none";

function useQuizApiClient(): QuizApiClient | null {
  const { authenticatedRequests } = useAuth();
  return useMemo(
    () =>
      authenticatedRequests === null
        ? null
        : createQuizApiClient({ authenticatedRequests }),
    [authenticatedRequests],
  );
}

function failureMessage(failure: QuizApiFailure): string {
  if (failure.kind === "api-error") {
    return failure.error.message;
  }
  if (failure.kind === "transport-error") {
    return "Quizopia could not reach the Quiz service. Your editor content is still here; try again.";
  }
  if (failure.kind === "authentication-failure") {
    return "Your authenticated session is not available for this request.";
  }
  if (failure.kind === "validation-error") {
    return "The backend rejected the Quiz Markdown. Review the validation details and try again.";
  }
  return `The Quiz service returned an unexpected response (${failure.status}).`;
}

function inputSnapshot(input: QuizDraftInput): string {
  return JSON.stringify(input);
}

function libraryFailureTitle(failure: QuizApiFailure): string {
  if (failure.kind === "api-error" && failure.error.status === 401) {
    return "Quiz library authentication required";
  }
  if (failure.kind === "api-error" && failure.error.status === 403) {
    return "Quiz library access denied";
  }
  return "Quiz library could not be loaded";
}

function libraryFailureMessage(failure: QuizApiFailure): string {
  if (failure.kind === "transport-error") {
    return "Quizopia could not reach the Quiz service. Try again without losing the quizzes already loaded on this page.";
  }
  return failureMessage(failure);
}

function appendUniqueLibraryItems(
  current: readonly QuizLibraryItem[],
  incoming: readonly QuizLibraryItem[],
): readonly QuizLibraryItem[] {
  const seen = new Set(current.map((item) => item.quizId));
  return [
    ...current,
    ...incoming.filter((item) => {
      if (seen.has(item.quizId)) {
        return false;
      }
      seen.add(item.quizId);
      return true;
    }),
  ];
}

function LibraryQuizCard({ item }: { readonly item: QuizLibraryItem }) {
  const title = item.title?.trim() || "Untitled quiz";
  const description = item.description?.trim();
  return (
    <li className="rounded-xl border border-border bg-surface p-5 shadow-card">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <Link
            className="break-words text-lg font-semibold text-foreground transition-colors hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none"
            href={`/app/quizzes/${item.quizId}`}
          >
            {title}
          </Link>
          {description ? (
            <p className="mt-2 break-words text-sm leading-6 text-foreground-secondary">
              {description}
            </p>
          ) : null}
          <p className="mt-3 break-all text-xs font-medium text-foreground-muted">
            Quiz ID: {item.quizId}
          </p>
        </div>
        <span className="shrink-0 rounded-md bg-surface-muted px-2.5 py-1.5 text-xs font-semibold text-foreground-secondary">
          {item.latestVersionNumber === null
            ? "Draft only"
            : `Latest version ${item.latestVersionNumber}`}
        </span>
      </div>
      <dl className="mt-4 grid gap-2 border-t border-border pt-4 text-xs text-foreground-muted sm:grid-cols-2">
        <div>
          <dt className="font-semibold text-foreground-secondary">Created</dt>
          <dd>
            <time dateTime={item.createdAt}>{item.createdAt}</time>
          </dd>
        </div>
        <div>
          <dt className="font-semibold text-foreground-secondary">Updated</dt>
          <dd>
            <time dateTime={item.updatedAt}>{item.updatedAt}</time>
          </dd>
        </div>
      </dl>
    </li>
  );
}

export function QuizLibraryPage() {
  const client = useQuizApiClient();
  const [libraryState, setLibraryState] = useState<{
    readonly client: QuizApiClient | null;
    readonly failure: QuizApiFailure | null;
    readonly items: readonly QuizLibraryItem[];
    readonly nextCursor: string | null;
  }>({ client: null, failure: null, items: [], nextCursor: null });
  const [paginationState, setPaginationState] = useState<{
    readonly client: QuizApiClient | null;
    readonly failure: QuizApiFailure | null;
    readonly isLoading: boolean;
  }>({ client: null, failure: null, isLoading: false });

  const stateMatchesClient = client !== null && libraryState.client === client;
  const items = stateMatchesClient ? libraryState.items : [];
  const nextCursor = stateMatchesClient ? libraryState.nextCursor : null;
  const initialFailure = stateMatchesClient ? libraryState.failure : null;
  const isLoading = client === null || !stateMatchesClient;
  const paginationMatchesClient =
    client !== null && paginationState.client === client;
  const isLoadingMore = paginationMatchesClient && paginationState.isLoading;
  const paginationFailure = paginationMatchesClient
    ? paginationState.failure
    : null;

  useEffect(() => {
    if (client === null) {
      return;
    }
    const controller = new AbortController();

    void client.listOwnedQuizzes({}, controller.signal).then((result) => {
      if (controller.signal.aborted) {
        return;
      }
      if (!result.ok) {
        setLibraryState({
          client,
          failure: result.error,
          items: [],
          nextCursor: null,
        });
        return;
      }
      setLibraryState({
        client,
        failure: null,
        items: result.value.items,
        nextCursor: result.value.nextCursor,
      });
    });

    return () => controller.abort();
  }, [client]);

  async function loadMore() {
    if (client === null || nextCursor === null || isLoadingMore) {
      return;
    }
    const cursor = nextCursor;
    setPaginationState({ client, failure: null, isLoading: true });
    const result = await client.listOwnedQuizzes({ cursor });
    if (!result.ok) {
      setPaginationState({ client, failure: result.error, isLoading: false });
      return;
    }
    setLibraryState((current) =>
      current.client === client
        ? {
            client,
            failure: null,
            items: appendUniqueLibraryItems(current.items, result.value.items),
            nextCursor: result.value.nextCursor,
          }
        : current,
    );
    setPaginationState({ client, failure: null, isLoading: false });
  }

  return (
    <div className="space-y-8">
      <div className="flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
        <div className="max-w-2xl">
          <p className="text-sm font-semibold text-primary">
            Teaching workspace
          </p>
          <h1 className="mt-2 text-3xl font-bold tracking-[-0.02em] text-foreground">
            Quiz authoring
          </h1>
          <p className="mt-3 text-base leading-7 text-foreground-secondary">
            Create a real teacher-owned draft, then author, preview, save, and
            publish its Quiz Markdown.
          </p>
        </div>
        <Link className={linkButtonClasses} href="/app/quizzes/new">
          Create quiz
        </Link>
      </div>

      <section aria-labelledby="quiz-library-title" className="max-w-4xl">
        <h2
          className="text-xl font-semibold text-foreground"
          id="quiz-library-title"
        >
          Your quizzes
        </h2>
        <p className="mt-2 text-sm leading-6 text-foreground-muted">
          Your library is loaded from the Quiz service and ordered by most
          recently updated.
        </p>

        {client === null || isLoading ? (
          <Surface className="mt-5 flex min-h-40 items-center justify-center gap-3 p-6">
            <LoadingIndicator label="Loading quiz library" />
            <span className="text-sm font-medium text-foreground-secondary">
              Loading quiz library…
            </span>
          </Surface>
        ) : initialFailure !== null ? (
          <Alert
            className="mt-5"
            title={libraryFailureTitle(initialFailure)}
            variant="danger"
          >
            {libraryFailureMessage(initialFailure)}
          </Alert>
        ) : items.length === 0 ? (
          <Surface className="mt-5 p-6 sm:p-8">
            <h3 className="text-lg font-semibold text-foreground">
              No quizzes yet
            </h3>
            <p className="mt-2 text-sm leading-6 text-foreground-secondary">
              Create your first quiz to start authoring and publishing Quiz
              Markdown.
            </p>
            <Link
              className={`${linkButtonClasses} mt-5`}
              href="/app/quizzes/new"
            >
              Create your first quiz
            </Link>
          </Surface>
        ) : (
          <div className="mt-5">
            <ul aria-live="polite" className="space-y-4">
              {items.map((item) => (
                <LibraryQuizCard item={item} key={item.quizId} />
              ))}
            </ul>

            {paginationFailure !== null ? (
              <Alert
                className="mt-5"
                title="More quizzes could not be loaded"
                variant="danger"
              >
                {libraryFailureMessage(paginationFailure)}
              </Alert>
            ) : null}

            {nextCursor !== null ? (
              <Button
                className="mt-5"
                isLoading={isLoadingMore}
                loadingLabel="Loading more quizzes"
                onClick={() => void loadMore()}
                variant="secondary"
              >
                Load more
              </Button>
            ) : (
              <p className="mt-5 text-sm text-foreground-muted" role="status">
                All quizzes loaded.
              </p>
            )}
          </div>
        )}
      </section>
    </div>
  );
}

export function CreateQuizPage() {
  const client = useQuizApiClient();
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [isCreating, setIsCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (client === null || isCreating) {
      return;
    }

    setError(null);
    setIsCreating(true);
    const result = await client.createDraft({
      authoringSource: "",
      description,
      title,
    });
    if (!result.ok) {
      setError(failureMessage(result.error));
      setIsCreating(false);
      return;
    }

    router.push(`/app/quizzes/${result.value.quizId}`);
  }

  return (
    <div className="max-w-3xl">
      <Link
        className="text-sm font-semibold text-primary hover:text-primary-hover focus-visible:ring-2 focus-visible:ring-focus"
        href="/app/quizzes"
      >
        ← Quiz authoring
      </Link>
      <h1 className="mt-5 text-3xl font-bold tracking-[-0.02em] text-foreground">
        Create a quiz draft
      </h1>
      <p className="mt-3 text-base leading-7 text-foreground-secondary">
        This creates the real teacher-owned Quiz and mutable draft before
        opening the Markdown editor.
      </p>

      <Surface className="mt-8 p-6 sm:p-8">
        {error ? (
          <Alert
            className="mb-6"
            title="Quiz could not be created"
            variant="danger"
          >
            {error}
          </Alert>
        ) : null}
        <form
          className="space-y-6"
          onSubmit={(event) => void handleSubmit(event)}
        >
          <div>
            <label
              className="text-sm font-semibold text-foreground-secondary"
              htmlFor="quiz-title"
            >
              Title
            </label>
            <input
              className="mt-2 min-h-11 w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground outline-none focus:border-primary focus:ring-2 focus:ring-focus/30"
              id="quiz-title"
              onChange={(event) => setTitle(event.target.value)}
              placeholder="Networking fundamentals"
              value={title}
            />
          </div>
          <div>
            <label
              className="text-sm font-semibold text-foreground-secondary"
              htmlFor="quiz-description"
            >
              Description
            </label>
            <textarea
              className="mt-2 min-h-28 w-full resize-y rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground outline-none focus:border-primary focus:ring-2 focus:ring-focus/30"
              id="quiz-description"
              onChange={(event) => setDescription(event.target.value)}
              placeholder="Optional context for this quiz"
              value={description}
            />
          </div>
          <div className="flex flex-wrap gap-3">
            <Button
              disabled={client === null}
              isLoading={isCreating}
              loadingLabel="Creating quiz"
              type="submit"
            >
              Create and open editor
            </Button>
            <Link
              className="inline-flex min-h-11 items-center justify-center rounded-lg border border-border-strong bg-surface px-4 py-2.5 text-sm font-semibold text-foreground-secondary hover:bg-surface-muted focus-visible:ring-2 focus-visible:ring-focus"
              href="/app/quizzes"
            >
              Cancel
            </Link>
          </div>
        </form>
      </Surface>
    </div>
  );
}

type LoadState = "loading" | "ready" | "forbidden" | "not-found" | "error";

export interface QuizEditorPageProps {
  readonly quizId: string;
}

export function QuizEditorPage({ quizId }: QuizEditorPageProps) {
  const client = useQuizApiClient();
  const editorRef = useRef<QuizMarkdownEditorHandle>(null);
  const [loadState, setLoadState] = useState<LoadState>("loading");
  const [loadError, setLoadError] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [source, setSource] = useState("");
  const [savedSnapshot, setSavedSnapshot] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [isPublishing, setIsPublishing] = useState(false);
  const [publishMessage, setPublishMessage] = useState<string | null>(null);
  const [publishError, setPublishError] = useState<string | null>(null);
  const [serverDiagnostics, setServerDiagnostics] = useState<
    readonly QuizMarkdownServerError[]
  >([]);
  const [mobilePane, setMobilePane] = useState<"source" | "preview">("source");

  const currentInput = useMemo<QuizDraftInput>(
    () => ({ authoringSource: source, description, title }),
    [description, source, title],
  );
  const dirty =
    savedSnapshot !== null && inputSnapshot(currentInput) !== savedSnapshot;

  useEffect(() => {
    if (client === null) {
      return;
    }
    const controller = new AbortController();

    void client.getDraft(quizId, controller.signal).then((result) => {
      if (controller.signal.aborted) {
        return;
      }
      if (!result.ok) {
        if (
          result.error.kind === "api-error" &&
          result.error.error.status === 403
        ) {
          setLoadState("forbidden");
          return;
        }
        if (
          result.error.kind === "api-error" &&
          result.error.error.status === 404
        ) {
          setLoadState("not-found");
          return;
        }
        setLoadError(failureMessage(result.error));
        setLoadState("error");
        return;
      }

      const nextInput = {
        authoringSource: result.value.authoringSource ?? "",
        description: result.value.description ?? "",
        title: result.value.title ?? "",
      };
      setTitle(nextInput.title);
      setDescription(nextInput.description);
      setSource(nextInput.authoringSource);
      setSavedSnapshot(inputSnapshot(nextInput));
      setLoadState("ready");
    });

    return () => controller.abort();
  }, [client, quizId]);

  async function saveDraft(): Promise<boolean> {
    if (client === null || isSaving) {
      return false;
    }
    setSaveError(null);
    setPublishMessage(null);
    setIsSaving(true);
    const input = currentInput;
    const result = await client.updateDraft(quizId, input);
    setIsSaving(false);
    if (!result.ok) {
      setSaveError(failureMessage(result.error));
      return false;
    }
    setSavedSnapshot(inputSnapshot(input));
    return true;
  }

  async function publishDraft() {
    if (client === null || isPublishing || isSaving) {
      return;
    }
    setPublishError(null);
    setPublishMessage(null);
    setServerDiagnostics([]);
    setIsPublishing(true);

    if (dirty && !(await saveDraft())) {
      setIsPublishing(false);
      return;
    }

    const result = await client.publishDraft(quizId);
    setIsPublishing(false);
    if (!result.ok) {
      if (result.error.kind === "validation-error") {
        setServerDiagnostics(result.error.errors);
        setPublishError(
          "The backend found Quiz Markdown errors. Select a validation item to move the editor caret to its source location.",
        );
        return;
      }
      setPublishError(failureMessage(result.error));
      return;
    }
    setPublishMessage(
      result.value.created
        ? `Published immutable version ${result.value.version.versionNumber}.`
        : `Version ${result.value.version.versionNumber} already matches this unchanged draft.`,
    );
  }

  if (client === null || loadState === "loading") {
    return (
      <div
        className="flex min-h-72 items-center justify-center gap-3"
        role="status"
      >
        <LoadingIndicator label="Loading quiz draft" />
        <span className="text-sm font-medium text-foreground-secondary">
          Loading quiz draft…
        </span>
      </div>
    );
  }

  if (loadState === "forbidden") {
    return (
      <Surface className="max-w-2xl p-6 sm:p-8">
        <h1 className="text-2xl font-bold text-foreground">
          Quiz access denied
        </h1>
        <Alert
          className="mt-5"
          title="You cannot edit this quiz"
          variant="danger"
        >
          The Quiz service denied this teacher-authoring request. Authorization
          and quiz ownership are enforced by the backend.
        </Alert>
      </Surface>
    );
  }

  if (loadState === "not-found") {
    return (
      <Surface className="max-w-2xl p-6 sm:p-8">
        <h1 className="text-2xl font-bold text-foreground">
          Quiz draft not found
        </h1>
        <p className="mt-3 text-base leading-7 text-foreground-secondary">
          This Quiz ID does not resolve to an available draft for the current
          request.
        </p>
        <Link className={`${linkButtonClasses} mt-6`} href="/app/quizzes">
          Back to quiz authoring
        </Link>
      </Surface>
    );
  }

  if (loadState === "error") {
    return (
      <Surface className="max-w-2xl p-6 sm:p-8">
        <h1 className="text-2xl font-bold text-foreground">
          Quiz could not be loaded
        </h1>
        <Alert className="mt-5" title="Loading failed" variant="danger">
          {loadError ?? "The quiz draft could not be loaded."}
        </Alert>
      </Surface>
    );
  }

  return (
    <div className="min-w-0">
      <div className="flex flex-col gap-5 xl:flex-row xl:items-start xl:justify-between">
        <div className="min-w-0 max-w-3xl">
          <Link
            className="text-sm font-semibold text-primary hover:text-primary-hover focus-visible:ring-2 focus-visible:ring-focus"
            href="/app/quizzes"
          >
            ← Quiz authoring
          </Link>
          <h1 className="mt-4 break-words text-3xl font-bold tracking-[-0.02em] text-foreground">
            {title.trim().length > 0 ? title : "Untitled quiz"}
          </h1>
          <p className="mt-2 break-all text-xs font-medium text-foreground-muted">
            Quiz ID: {quizId}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <span
            aria-live="polite"
            className="text-sm font-medium text-foreground-secondary"
          >
            {isSaving ? "Saving…" : dirty ? "Unsaved changes" : "Saved"}
          </span>
          <Button
            disabled={!dirty || isPublishing}
            isLoading={isSaving}
            loadingLabel="Saving draft"
            onClick={() => void saveDraft()}
            variant="secondary"
          >
            Save
          </Button>
          <Button
            disabled={isSaving}
            isLoading={isPublishing}
            loadingLabel="Publishing quiz"
            onClick={() => void publishDraft()}
          >
            Publish version
          </Button>
        </div>
      </div>

      {saveError ? (
        <Alert className="mt-6" title="Draft was not saved" variant="danger">
          {saveError}
        </Alert>
      ) : null}
      {publishError ? (
        <Alert className="mt-6" title="Quiz was not published" variant="danger">
          {publishError}
        </Alert>
      ) : null}
      {publishMessage ? (
        <Alert className="mt-6" title="Publish complete" variant="success">
          {publishMessage}
        </Alert>
      ) : null}

      <section
        className="mt-7 grid gap-5 rounded-xl border border-border bg-surface p-5 sm:p-6 lg:grid-cols-2"
        aria-labelledby="quiz-details-title"
      >
        <h2 className="sr-only" id="quiz-details-title">
          Quiz details
        </h2>
        <div>
          <label
            className="text-sm font-semibold text-foreground-secondary"
            htmlFor="editor-title"
          >
            Title
          </label>
          <input
            className="mt-2 min-h-11 w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground outline-none focus:border-primary focus:ring-2 focus:ring-focus/30"
            disabled={isSaving || isPublishing}
            id="editor-title"
            onChange={(event) => setTitle(event.target.value)}
            value={title}
          />
        </div>
        <div>
          <label
            className="text-sm font-semibold text-foreground-secondary"
            htmlFor="editor-description"
          >
            Description
          </label>
          <input
            className="mt-2 min-h-11 w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground outline-none focus:border-primary focus:ring-2 focus:ring-focus/30"
            disabled={isSaving || isPublishing}
            id="editor-description"
            onChange={(event) => setDescription(event.target.value)}
            value={description}
          />
        </div>
      </section>

      <div
        className="mt-6 flex rounded-lg border border-border-strong bg-surface p-1 lg:hidden"
        aria-label="Editor pane"
        role="group"
      >
        {(["source", "preview"] as const).map((pane) => (
          <button
            aria-pressed={mobilePane === pane}
            className={`min-h-11 flex-1 rounded-md px-3 py-2 text-sm font-semibold ${
              mobilePane === pane
                ? "bg-primary text-foreground-inverse"
                : "text-foreground-secondary hover:bg-surface-muted"
            }`}
            key={pane}
            onClick={() => setMobilePane(pane)}
            type="button"
          >
            {pane === "source" ? "Source" : "Preview"}
          </button>
        ))}
      </div>

      <div className="mt-5 grid min-w-0 gap-5 lg:grid-cols-[minmax(0,1.15fr)_minmax(0,0.85fr)]">
        <section
          className={`${mobilePane === "source" ? "block" : "hidden"} min-w-0 rounded-xl border border-border bg-surface p-4 sm:p-5 lg:block`}
        >
          <QuizMarkdownEditor
            disabled={isSaving || isPublishing}
            onChange={(value) => {
              setSource(value);
              setServerDiagnostics([]);
              setPublishError(null);
              setPublishMessage(null);
            }}
            ref={editorRef}
            value={source}
          />
        </section>
        <section
          className={`${mobilePane === "preview" ? "block" : "hidden"} min-w-0 rounded-xl border border-border bg-surface-muted/50 p-4 sm:p-5 lg:block`}
        >
          <QuizPreview
            onDiagnosticSelect={(line, column) => {
              setMobilePane("source");
              requestAnimationFrame(() =>
                editorRef.current?.focusLocation(line, column),
              );
            }}
            serverDiagnostics={serverDiagnostics}
            source={source}
          />
        </section>
      </div>
    </div>
  );
}
