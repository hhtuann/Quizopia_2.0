"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  useEffect,
  useLayoutEffect,
  useMemo,
  useReducer,
  useRef,
  useState,
} from "react";
import { QuizopiaLogo } from "../../../components/brand/quizopia-logo";
import { APPLICATION_HEADER_GEOMETRY } from "../../../components/ui/application-header-geometry";
import { Alert } from "../../../components/ui/alert";
import {
  Button,
  OUTLINE_LINK_CLASSES,
  PRIMARY_LINK_CLASSES,
} from "../../../components/ui/button";
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
  analyzeQuizMarkdown,
  toggleQuizOptionCorrectness,
  type QuizPreviewOption,
  type QuizPreviewQuestion,
} from "../model/quiz-markdown";
import { sourceLocationToOffset } from "../model/quiz-markdown-coordinates";
import {
  QuizMarkdownCodeEditor,
  type QuizMarkdownCodeEditorHandle,
} from "./quiz-markdown-code-editor";
import { PublishedVersionHistory } from "./published-version-history";
import { QuizPreview } from "./quiz-preview";

const linkButtonClasses = PRIMARY_LINK_CLASSES;

function formatLibraryDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat("en", {
        day: "numeric",
        month: "short",
        year: "numeric",
        timeZone: "UTC",
      }).format(date);
}

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

function optionContentOffset(
  source: string,
  question: QuizPreviewQuestion,
  option: QuizPreviewOption,
): number | null {
  const updatedQuestion = analyzeQuizMarkdown(source).questions.find(
    (candidate) =>
      candidate.number === question.number &&
      candidate.source.line === question.source.line,
  );
  const updatedOption = updatedQuestion?.options.find(
    (candidate) => candidate.label === option.label,
  );
  if (updatedOption === undefined) {
    return null;
  }

  let offset = updatedOption.source.markerOffset + 2;
  while (source[offset] === " " || source[offset] === "\t") {
    offset += 1;
  }
  return offset;
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
    <li className="quiz-interactive-card group rounded-xl border border-border bg-surface p-5 shadow-card sm:p-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <Link
            className="break-words text-lg font-bold text-foreground transition-colors hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none"
            href={`/app/quizzes/${item.quizId}`}
          >
            {title}
          </Link>
          {description ? (
            <p className="mt-2 break-words text-sm leading-6 text-foreground-secondary">
              {description}
            </p>
          ) : null}
          <p className="mt-3 break-all font-mono text-[0.7rem] text-foreground-muted">
            Quiz ID: {item.quizId}
          </p>
        </div>
        <span className="shrink-0 self-start rounded-full border border-primary/15 bg-primary/5 px-3 py-1.5 text-xs font-semibold text-primary">
          {item.latestVersionNumber === null
            ? "Draft only"
            : `Latest version ${item.latestVersionNumber}`}
        </span>
      </div>
      <dl className="mt-5 grid gap-3 border-t border-border pt-4 text-xs text-foreground-muted sm:grid-cols-2">
        <div>
          <dt className="font-semibold text-foreground-secondary">Created</dt>
          <dd>
            <time dateTime={item.createdAt} title={item.createdAt}>
              {formatLibraryDate(item.createdAt)}
            </time>
          </dd>
        </div>
        <div>
          <dt className="font-semibold text-foreground-secondary">Updated</dt>
          <dd>
            <time dateTime={item.updatedAt} title={item.updatedAt}>
              {formatLibraryDate(item.updatedAt)}
            </time>
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
      <div className="relative flex flex-col gap-5 overflow-hidden rounded-2xl border border-primary/10 bg-surface p-6 shadow-card sm:flex-row sm:items-center sm:justify-between sm:p-8">
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -right-10 -top-20 size-72 rounded-full bg-gradient-to-br from-primary/10 to-secondary/10 blur-3xl"
        />
        <div className="max-w-2xl">
          <p className="text-xs font-bold uppercase tracking-[0.14em] text-primary">
            Teaching workspace
          </p>
          <h1 className="mt-3 font-heading text-3xl font-extrabold tracking-[-0.035em] text-foreground sm:text-4xl">
            Quiz authoring
          </h1>
          <p className="mt-3 text-base leading-7 text-foreground-secondary">
            Create a real teacher-owned draft, then author, preview, save, and
            publish its Quiz Markdown.
          </p>
        </div>
        <Link
          className={`${linkButtonClasses} relative shrink-0`}
          href="/app/quizzes/new"
        >
          <span aria-hidden="true">+</span> Create quiz
        </Link>
      </div>

      <section aria-labelledby="quiz-library-title">
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
          <Surface className="mt-5 flex flex-col items-start bg-[radial-gradient(ellipse_at_top_right,rgba(79,70,229,0.06),transparent_65%)] p-8 sm:p-12">
            <div
              aria-hidden="true"
              className="mb-4 flex size-12 items-center justify-center rounded-2xl bg-primary/10 text-2xl font-bold text-primary"
            >
              +
            </div>
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
            <ul
              aria-live="polite"
              className="grid gap-4 xl:grid-cols-2 2xl:grid-cols-3"
            >
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
  const creationStarted = useRef(false);
  const creationPageIsActive = useRef(false);
  const [attempt, setAttempt] = useState(0);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (client === null) {
      return;
    }
    creationPageIsActive.current = true;

    if (creationStarted.current) {
      return () => {
        creationPageIsActive.current = false;
      };
    }
    creationStarted.current = true;

    void client
      .createDraft({
        authoringSource: "",
        description: "",
        title: "Untitled quiz",
      })
      .then((result) => {
        if (!creationPageIsActive.current) {
          return;
        }
        if (!result.ok) {
          setError(failureMessage(result.error));
          return;
        }
        router.replace(`/app/quizzes/${result.value.quizId}`);
      });

    return () => {
      creationPageIsActive.current = false;
    };
  }, [attempt, client, router]);

  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="flex min-h-16 shrink-0 items-center gap-3 border-b border-border bg-surface px-4 sm:px-6">
        <Link
          aria-label="Back to Quiz Library"
          className="flex min-h-11 items-center rounded-lg text-foreground hover:text-primary focus-visible:ring-2 focus-visible:ring-focus"
          href="/app/quizzes"
        >
          <QuizopiaLogo
            markClassName="size-9 rounded-lg"
            wordmarkClassName="hidden sm:flex"
          />
        </Link>
        <span aria-hidden="true" className="h-6 w-px bg-border" />
        <span className="text-sm font-semibold text-foreground-secondary">
          New quiz
        </span>
      </header>
      <div className="flex min-h-0 flex-1 items-center justify-center p-4">
        <Surface className="w-full max-w-lg p-6 text-center sm:p-8">
          {error ? (
            <>
              <Alert title="Quiz could not be created" variant="danger">
                {error}
              </Alert>
              <div className="mt-5 flex justify-center gap-3">
                <Button
                  onClick={() => {
                    creationStarted.current = false;
                    setError(null);
                    setAttempt((current) => current + 1);
                  }}
                >
                  Try again
                </Button>
                <Link className={OUTLINE_LINK_CLASSES} href="/app/quizzes">
                  Back to library
                </Link>
              </div>
            </>
          ) : (
            <div
              className="flex items-center justify-center gap-3"
              role="status"
            >
              <LoadingIndicator label="Creating quiz editor" />
              <span className="text-sm font-medium text-foreground-secondary">
                Preparing your editor…
              </span>
            </div>
          )}
        </Surface>
      </div>
    </div>
  );
}

type LoadState = "loading" | "ready" | "forbidden" | "not-found" | "error";

interface PendingEditorFocus {
  readonly offset: number;
  readonly suppressAutocomplete: boolean;
}

export interface QuizEditorPageProps {
  readonly quizId: string;
}

export function QuizEditorPage({ quizId }: QuizEditorPageProps) {
  const router = useRouter();
  const client = useQuizApiClient();
  const editorRef = useRef<QuizMarkdownCodeEditorHandle>(null);
  const historyTriggerRef = useRef<HTMLButtonElement>(null);
  const pendingEditorFocusRef = useRef<PendingEditorFocus | null>(null);
  const [focusRequestVersion, requestEditorFocus] = useReducer(
    (version: number) => version + 1,
    0,
  );
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
  const [publishDialogOpen, setPublishDialogOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyRefreshVersion, setHistoryRefreshVersion] = useState(0);

  const currentInput = useMemo<QuizDraftInput>(
    () => ({ authoringSource: source, description, title }),
    [description, source, title],
  );
  const dirty =
    savedSnapshot !== null && inputSnapshot(currentInput) !== savedSnapshot;

  function backToApp() {
    if (
      !dirty ||
      window.confirm(
        "You have unsaved changes. Leave the editor and discard them?",
      )
    ) {
      router.push("/app");
    }
  }

  useLayoutEffect(() => {
    const pendingEditorFocus = pendingEditorFocusRef.current;
    if (pendingEditorFocus === null || mobilePane !== "source") {
      return;
    }
    pendingEditorFocusRef.current = null;
    editorRef.current?.focusOffset(pendingEditorFocus.offset, {
      suppressAutocomplete: pendingEditorFocus.suppressAutocomplete,
    });
  }, [focusRequestVersion, mobilePane, source]);

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
    setHistoryRefreshVersion((current) => current + 1);
    setPublishDialogOpen(false);
  }

  function updateSource(value: string) {
    setSource(value);
    setServerDiagnostics([]);
    setPublishError(null);
    setPublishMessage(null);
  }

  function handleOptionToggle(
    question: QuizPreviewQuestion,
    option: QuizPreviewOption,
  ) {
    const nextSource = toggleQuizOptionCorrectness(source, question, option);
    if (nextSource === source) {
      return;
    }
    const caretOffset = optionContentOffset(nextSource, question, option);
    if (caretOffset !== null) {
      pendingEditorFocusRef.current = {
        offset: caretOffset,
        suppressAutocomplete: true,
      };
      requestEditorFocus();
    }
    updateSource(nextSource);
    setMobilePane("source");
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
        <h1 className="font-heading text-2xl font-normal text-foreground">
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
        <h1 className="font-heading text-2xl font-normal text-foreground">
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
        <h1 className="font-heading text-2xl font-normal text-foreground">
          Quiz could not be loaded
        </h1>
        <Alert className="mt-5" title="Loading failed" variant="danger">
          {loadError ?? "The quiz draft could not be loaded."}
        </Alert>
      </Surface>
    );
  }

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col overflow-hidden">
      <header
        aria-hidden={historyOpen || undefined}
        className={APPLICATION_HEADER_GEOMETRY}
        inert={historyOpen ? true : undefined}
      >
        <Link
          aria-label="Back to Quiz Library"
          className="flex min-h-11 shrink-0 items-center rounded-lg text-foreground hover:text-primary focus-visible:ring-2 focus-visible:ring-focus"
          href="/app/quizzes"
        >
          <QuizopiaLogo
            markClassName="size-9 rounded-lg"
            wordmarkClassName="hidden xl:flex"
          />
        </Link>
        <Button
          aria-label="Back to app"
          className="size-11 shrink-0 p-0"
          disabled={isSaving || isPublishing}
          onClick={backToApp}
          variant="secondary"
        >
          <svg
            aria-hidden="true"
            className="size-5"
            fill="none"
            focusable="false"
            stroke="currentColor"
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth="2"
            viewBox="0 0 24 24"
          >
            <path d="m15 18-6-6 6-6" />
          </svg>
        </Button>
        <span
          aria-hidden="true"
          className="hidden h-7 w-px bg-border sm:block"
        />
        <div className="order-3 flex w-full min-w-0 items-center gap-3 sm:order-none sm:w-auto sm:min-w-[16rem] sm:flex-1 sm:max-w-xl">
          <label
            className="shrink-0 text-xs font-semibold text-foreground-secondary"
            htmlFor="editor-title"
          >
            Quiz title
          </label>
          <input
            className="min-h-11 min-w-0 flex-1 rounded-lg border border-border-strong bg-surface px-3 py-2 text-base font-semibold text-foreground shadow-sm outline-none transition-colors hover:border-primary focus-visible:border-primary focus-visible:ring-2 focus-visible:ring-focus"
            disabled={isSaving || isPublishing}
            id="editor-title"
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Untitled quiz"
            value={title}
          />
        </div>
        <div className="order-4 ml-auto flex w-full flex-wrap items-center gap-2 sm:order-none sm:w-auto">
          <span
            aria-live="polite"
            className="hidden font-mono text-xs font-semibold text-foreground-muted sm:inline"
          >
            {isSaving ? "Saving…" : dirty ? "Unsaved changes" : "Saved"}
          </span>
          <Button
            aria-expanded={historyOpen}
            aria-haspopup="dialog"
            className="px-3 sm:px-4"
            disabled={isSaving || isPublishing}
            onClick={() => setHistoryOpen(true)}
            ref={historyTriggerRef}
            variant="ghost"
          >
            Published versions
          </Button>
          <Button
            className="px-3 sm:px-4"
            disabled={!dirty || isPublishing}
            isLoading={isSaving}
            loadingLabel="Saving draft"
            onClick={() => void saveDraft()}
            variant="brand-outline"
          >
            Save
          </Button>
          <Button
            className="px-3 sm:px-5"
            disabled={isSaving}
            onClick={() => setPublishDialogOpen(true)}
          >
            Publish
          </Button>
        </div>
      </header>

      <div
        aria-hidden={historyOpen || undefined}
        className="flex min-h-0 flex-1 flex-col gap-2 p-2 sm:gap-3 sm:p-3"
        inert={historyOpen ? true : undefined}
      >
        {saveError || publishError || publishMessage ? (
          <div className="shrink-0">
            {saveError ? (
              <Alert title="Draft was not saved" variant="danger">
                {saveError}
              </Alert>
            ) : null}
            {publishError ? (
              <Alert title="Quiz was not published" variant="danger">
                {publishError}
              </Alert>
            ) : null}
            {publishMessage ? (
              <Alert title="Publish complete" variant="success">
                {publishMessage}
              </Alert>
            ) : null}
          </div>
        ) : null}

        <div
          aria-label="Editor pane"
          className="flex shrink-0 rounded-lg border border-border-strong bg-surface p-1 lg:hidden"
          role="group"
        >
          {(["source", "preview"] as const).map((pane) => (
            <button
              aria-pressed={mobilePane === pane}
              className={`min-h-11 flex-1 rounded-md px-3 py-2 text-sm font-semibold ${
                mobilePane === pane
                  ? "brand-gradient text-foreground-inverse shadow-primary"
                  : "text-foreground-secondary hover:bg-surface-muted"
              }`}
              key={pane}
              onClick={() => setMobilePane(pane)}
              type="button"
            >
              {pane === "source" ? "Editor" : "Preview"}
            </button>
          ))}
        </div>

        <div className="grid min-h-0 min-w-0 flex-1 gap-3 lg:grid-cols-2">
          <section
            aria-label="Quiz Markdown editor"
            className={`${mobilePane === "source" ? "block" : "hidden"} min-h-0 min-w-0 overflow-hidden rounded-xl border border-primary/10 bg-surface p-3 shadow-card sm:p-4 lg:block`}
          >
            <QuizMarkdownCodeEditor
              disabled={isSaving || isPublishing}
              onChange={updateSource}
              ref={editorRef}
              value={source}
            />
          </section>
          <section
            aria-label="Live quiz preview"
            className={`${mobilePane === "preview" ? "block" : "hidden"} min-h-0 min-w-0 overflow-y-auto rounded-xl border border-primary/10 bg-surface-muted/50 p-3 shadow-card sm:p-4 lg:block`}
          >
            <QuizPreview
              onDiagnosticSelect={(line, column) => {
                setMobilePane("source");
                pendingEditorFocusRef.current = {
                  offset: sourceLocationToOffset(source, line, column),
                  suppressAutocomplete: true,
                };
                requestEditorFocus();
              }}
              onOptionToggle={handleOptionToggle}
              onQuestionSelect={(question) => {
                setMobilePane("source");
                pendingEditorFocusRef.current = {
                  offset: question.source.offset,
                  suppressAutocomplete: true,
                };
                requestEditorFocus();
              }}
              serverDiagnostics={serverDiagnostics}
              source={source}
            />
          </section>
        </div>
      </div>

      <PublishedVersionHistory
        client={client}
        isOpen={historyOpen}
        onClose={() => setHistoryOpen(false)}
        quizId={quizId}
        refreshVersion={historyRefreshVersion}
        triggerRef={historyTriggerRef}
      />

      {publishDialogOpen ? (
        <div
          aria-labelledby="publish-dialog-title"
          aria-modal="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-foreground/35 p-4"
          onClick={(event) => {
            if (event.currentTarget === event.target && !isPublishing) {
              setPublishDialogOpen(false);
            }
          }}
          onKeyDown={(event) => {
            if (event.key === "Escape" && !isPublishing) {
              setPublishDialogOpen(false);
            }
          }}
          role="dialog"
        >
          <Surface className="w-full max-w-xl p-5 shadow-card sm:p-6">
            <h2
              className="text-xl font-semibold text-foreground"
              id="publish-dialog-title"
            >
              Publish immutable QuizVersion
            </h2>
            <p className="mt-2 text-sm leading-6 text-foreground-secondary">
              This publishes quiz content only. Assessment timing, audience, and
              classroom delivery remain a separate product flow.
            </p>
            <div className="mt-5">
              <label
                className="text-sm font-semibold text-foreground-secondary"
                htmlFor="publish-description"
              >
                Description
              </label>
              <textarea
                autoFocus
                className="mt-2 min-h-28 w-full resize-y rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground outline-none focus:border-primary focus:ring-2 focus:ring-focus/30"
                disabled={isPublishing}
                id="publish-description"
                onChange={(event) => setDescription(event.target.value)}
                placeholder="Optional context saved with this quiz draft"
                value={description}
              />
            </div>
            <div className="mt-6 flex flex-wrap justify-end gap-3">
              <Button
                disabled={isPublishing}
                onClick={() => setPublishDialogOpen(false)}
                variant="secondary"
              >
                Cancel
              </Button>
              <Button
                isLoading={isPublishing}
                loadingLabel="Publishing quiz"
                onClick={() => void publishDraft()}
              >
                Publish QuizVersion
              </Button>
            </div>
          </Surface>
        </div>
      ) : null}
    </div>
  );
}
