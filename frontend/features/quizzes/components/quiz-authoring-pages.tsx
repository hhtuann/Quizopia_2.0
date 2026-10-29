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
  type ReactNode,
} from "react";
import { QuizopiaLogo } from "../../../components/brand/quizopia-logo";
import { APPLICATION_HEADER_GEOMETRY } from "../../../components/ui/application-header-geometry";
import { Alert } from "../../../components/ui/alert";
import { Button, PRIMARY_LINK_CLASSES } from "../../../components/ui/button";
import { LoadingIndicator } from "../../../components/ui/loading-indicator";
import { FocusedBackButton } from "../../../components/ui/focused-back-button";
import {
  CheckIcon,
  PlusIcon,
  RetryIcon,
  SaveIcon,
  TrashIcon,
} from "../../../components/ui/icons";
import { Surface } from "../../../components/ui/surface";
import { HeroOrb } from "../../../components/ui/hero-orb";
import type { TranslationValues } from "../../../lib/i18n/core";
import { formatInstant } from "../../../lib/i18n/core";
import { useI18n } from "../../../lib/i18n/provider";
import { useUnsavedNavigationGuard } from "../../attempts/hooks/use-unsaved-navigation-guard";
import { useAuth } from "../../auth/auth-provider";
import {
  createQuizApiClient,
  type QuizApiClient,
  type QuizApiFailure,
  type QuizDeletionStatus,
  type QuizDraftInput,
  type QuizLibraryItem,
  type QuizMarkdownServerError,
} from "../api/quiz-api-client";
import {
  createQuizDeletionApiClient,
  type QuizDeletionAccepted,
  type QuizDeletionApiClient,
} from "../api/quiz-deletion-client";
import { isDeletionFenced } from "../model/quiz-deletion";
import {
  analyzeQuizMarkdown,
  toggleQuizOptionCorrectness,
  type QuizPreviewOption,
  type QuizPreviewQuestion,
} from "../model/quiz-markdown";
import { sourceLocationToOffset } from "../model/quiz-markdown-coordinates";
import { QuizDeletionDialog } from "./quiz-deletion-dialog";
import { QuizDeletionProgress } from "./quiz-deletion-progress";
import {
  QuizMarkdownCodeEditor,
  type QuizMarkdownCodeEditorHandle,
} from "./quiz-markdown-code-editor";
import { PublishedVersionHistory } from "./published-version-history";
import { QuizPreview } from "./quiz-preview";

const linkButtonClasses = PRIMARY_LINK_CLASSES;

type Localize = (message: string, values?: TranslationValues) => string;

// Load effects must not refetch when only the locale changes (that would
// discard in-flight authoring state), so they read the latest localizer
// through a stable ref instead of a reactive dependency.
function useLocalizeRef() {
  const { localize } = useI18n();
  const ref = useRef(localize);
  useLayoutEffect(() => {
    ref.current = localize;
  }, [localize]);
  return ref;
}

function useLibraryDateFormatter(): (value: string) => string {
  const { locale } = useI18n();
  return (value: string) => formatInstant(locale, value, { timeStyle: "none" });
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

function useQuizDeletionApiClient(): QuizDeletionApiClient | null {
  const { authenticatedRequests, getSessionGeneration } = useAuth();
  const generation = getSessionGeneration();
  return useMemo(
    () =>
      authenticatedRequests === null || generation === null
        ? null
        : createQuizDeletionApiClient({
            authenticatedRequests,
            getSessionGeneration,
          }),
    [authenticatedRequests, getSessionGeneration, generation],
  );
}

/**
 * Cross-navigation deletion notice: the editor sets it when a deletion
 * reaches its terminal state and navigates here; the library reads it once.
 * Preference-style storage only — never credentials or account data.
 */
const DELETION_NOTICE_STORAGE_KEY = "quizopia.quiz-deletion.notice";

function readPendingDeletionNotice(): { title: string } | null {
  try {
    const raw = window.sessionStorage.getItem(DELETION_NOTICE_STORAGE_KEY);
    if (raw === null) {
      return null;
    }
    window.sessionStorage.removeItem(DELETION_NOTICE_STORAGE_KEY);
    const parsed: unknown = JSON.parse(raw);
    if (
      typeof parsed === "object" &&
      parsed !== null &&
      typeof (parsed as { title?: unknown }).title === "string"
    ) {
      return { title: (parsed as { title: string }).title };
    }
    return null;
  } catch {
    return null;
  }
}

function writePendingDeletionNotice(title: string): void {
  try {
    window.sessionStorage.setItem(
      DELETION_NOTICE_STORAGE_KEY,
      JSON.stringify({ title }),
    );
  } catch {
    // Storage can be unavailable (private mode); the navigation still works.
  }
}

function isDeletionPendingFailure(failure: QuizApiFailure): boolean {
  return (
    failure.kind === "api-error" &&
    (failure.error.code === "QUIZ_DELETION_PENDING" ||
      failure.error.code === "DELETION_CONFLICT")
  );
}

function failureMessage(failure: QuizApiFailure, localize: Localize): string {
  if (failure.kind === "api-error") {
    // Server-authored diagnostics stay verbatim; they may embed source text.
    return failure.error.message;
  }
  if (failure.kind === "transport-error") {
    return localize(
      "Quizopia could not connect. Your editor content is still here; try again.",
    );
  }
  if (failure.kind === "authentication-failure") {
    return localize("Your session has ended. Please sign in again.");
  }
  if (failure.kind === "validation-error") {
    return localize(
      "The Quiz Markdown has errors. Review the details and try again.",
    );
  }
  return localize("Quizopia got an unexpected response ({status}).", {
    status: failure.status,
  });
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

function libraryFailureTitle(
  failure: QuizApiFailure,
  localize: Localize,
): string {
  if (failure.kind === "api-error" && failure.error.status === 401) {
    return localize("Quiz library authentication required");
  }
  if (failure.kind === "api-error" && failure.error.status === 403) {
    return localize("Quiz library access denied");
  }
  return localize("Quiz library could not be loaded");
}

function libraryFailureMessage(
  failure: QuizApiFailure,
  localize: Localize,
): string {
  if (failure.kind === "transport-error") {
    return localize(
      "Quizopia could not connect. Your loaded quizzes stay on this page; try again.",
    );
  }
  return failureMessage(failure, localize);
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

function LibraryQuizCard({
  formatLibraryDate,
  item,
  onDelete,
  progress,
}: {
  readonly formatLibraryDate: (value: string) => string;
  readonly item: QuizLibraryItem;
  /** Owner-only destructive entry; absent while a deletion already runs. */
  readonly onDelete: (item: QuizLibraryItem) => void;
  readonly progress: ReactNode;
}) {
  const { localize, t } = useI18n();
  const title = item.title?.trim() || t("Untitled quiz");
  const description = item.description?.trim();
  const deletionRunning = isDeletionFenced(item.deletionStatus);
  return (
    <li className="rounded-xl border border-border bg-surface p-5 shadow-card sm:p-6">
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
            {localize("Quiz ID: {quizId}", { quizId: item.quizId })}
          </p>
        </div>
        <div className="flex shrink-0 flex-col items-start gap-2 self-start sm:items-end">
          <span className="rounded-full border border-primary/15 bg-primary/5 px-3 py-1.5 text-xs font-semibold text-primary">
            {item.latestVersionNumber === null
              ? t("Draft only")
              : localize("Latest version {number}", {
                  number: item.latestVersionNumber,
                })}
          </span>
          {deletionRunning ? (
            <span className="rounded-full border border-danger/30 bg-danger/10 px-3 py-1.5 text-xs font-semibold text-danger">
              {t("Being deleted")}
            </span>
          ) : (
            <Button
              aria-label={localize("Delete quiz")}
              className="px-3"
              onClick={() => onDelete(item)}
              size="compact"
              variant="danger-outline"
            >
              <span className="inline-flex items-center gap-1.5">
                <TrashIcon className="size-4" />
                {t("Delete quiz")}
              </span>
            </Button>
          )}
        </div>
      </div>
      <dl className="mt-5 grid gap-3 border-t border-border pt-4 text-xs text-foreground-muted sm:grid-cols-2">
        <div>
          <dt className="font-semibold text-foreground-secondary">
            {t("Created")}
          </dt>
          <dd>
            <time dateTime={item.createdAt} title={item.createdAt}>
              {formatLibraryDate(item.createdAt)}
            </time>
          </dd>
        </div>
        <div>
          <dt className="font-semibold text-foreground-secondary">
            {t("Updated")}
          </dt>
          <dd>
            <time dateTime={item.updatedAt} title={item.updatedAt}>
              {formatLibraryDate(item.updatedAt)}
            </time>
          </dd>
        </div>
      </dl>
      {progress}
    </li>
  );
}

export function QuizLibraryPage() {
  const { localize, t } = useI18n();
  const client = useQuizApiClient();
  const deletionClient = useQuizDeletionApiClient();
  const formatLibraryDate = useLibraryDateFormatter();
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
  // FB-015 deletion state: dialog intent, followed operation, terminal notice.
  const [deletionDialog, setDeletionDialog] = useState<{
    readonly quizId: string;
    readonly title: string | null;
  } | null>(null);
  const [deletionProgress, setDeletionProgress] = useState<{
    readonly quizId: string;
    readonly operationId: string;
    readonly status: string;
    readonly title: string;
  } | null>(null);
  const [deletionNotice, setDeletionNotice] = useState<{
    readonly title: string;
  } | null>(() => readPendingDeletionNotice());
  const [libraryRefreshVersion, setLibraryRefreshVersion] = useState(0);

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
  }, [client, libraryRefreshVersion]);

  function handleDeletionAccepted(accepted: QuizDeletionAccepted) {
    if (deletionDialog === null) {
      return;
    }
    setDeletionDialog(null);
    setDeletionProgress({
      quizId: deletionDialog.quizId,
      operationId: accepted.operationId,
      status: accepted.status,
      title: deletionDialog.title?.trim() || t("Untitled quiz"),
    });
  }

  function handleDeletionTerminal() {
    const finished = deletionProgress;
    setDeletionProgress(null);
    setDeletionNotice({ title: finished?.title ?? t("Untitled quiz") });
    // The purge removes the row; reload so the library stays truthful.
    setLibraryRefreshVersion((current) => current + 1);
  }

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
        <HeroOrb className="-right-10 -top-20 size-72" />
        <div className="max-w-2xl">
          <p className="text-xs font-bold uppercase tracking-[0.14em] text-primary">
            {t("Teaching workspace")}
          </p>
          <h1 className="mt-3 font-heading text-3xl font-extrabold tracking-[-0.035em] text-foreground sm:text-4xl">
            {t("Quiz authoring")}
          </h1>
          <p className="mt-3 text-base leading-7 text-foreground-secondary">
            {t("Create a draft, write your quiz, preview it, and publish.")}
          </p>
        </div>
        <Link
          className={`${linkButtonClasses} relative shrink-0`}
          href="/app/quizzes/new"
        >
          <PlusIcon className="size-4" /> {t("Create quiz")}
        </Link>
      </div>

      <section aria-labelledby="quiz-library-title">
        <h2
          className="text-xl font-semibold text-foreground"
          id="quiz-library-title"
        >
          {t("Your quizzes")}
        </h2>
        <p className="mt-2 text-sm leading-6 text-foreground-muted">
          {t("Manage your quizzes.")}
        </p>

        {deletionNotice !== null ? (
          <Alert className="mt-5" title={t("Quiz deleted")} variant="success">
            {localize(
              "“{title}” was deleted. Students can no longer access it.",
              {
                title: deletionNotice.title,
              },
            )}
          </Alert>
        ) : null}

        {client === null || isLoading ? (
          <Surface className="mt-5 flex min-h-40 items-center justify-center gap-3 p-6">
            <LoadingIndicator label={t("Loading quiz library")} />
            <span className="text-sm font-medium text-foreground-secondary">
              {t("Loading quiz library…")}
            </span>
          </Surface>
        ) : initialFailure !== null ? (
          <Alert
            className="mt-5"
            title={libraryFailureTitle(initialFailure, localize)}
            variant="danger"
          >
            {libraryFailureMessage(initialFailure, localize)}
          </Alert>
        ) : items.length === 0 ? (
          <Surface className="mt-5 flex flex-col items-start p-8 sm:p-12">
            <div
              aria-hidden="true"
              className="mb-4 flex size-12 items-center justify-center rounded-2xl bg-primary/10 text-2xl font-bold text-primary"
            >
              <PlusIcon className="size-5" />
            </div>
            <h3 className="text-lg font-semibold text-foreground">
              {t("No quizzes yet")}
            </h3>
            <p className="mt-2 text-sm leading-6 text-foreground-secondary">
              {t(
                "Create your first quiz to start authoring and publishing Quiz Markdown.",
              )}
            </p>
            <Link
              className={`${linkButtonClasses} mt-5`}
              href="/app/quizzes/new"
            >
              {t("Create your first quiz")}
            </Link>
          </Surface>
        ) : (
          <div className="mt-5">
            <ul
              aria-live="polite"
              className="grid gap-4 xl:grid-cols-2 2xl:grid-cols-3"
            >
              {items.map((item) => (
                <LibraryQuizCard
                  formatLibraryDate={formatLibraryDate}
                  item={item}
                  key={item.quizId}
                  onDelete={(target) =>
                    setDeletionDialog({
                      quizId: target.quizId,
                      title: target.title,
                    })
                  }
                  progress={
                    deletionProgress !== null &&
                    deletionProgress.quizId === item.quizId &&
                    deletionClient !== null ? (
                      <div className="mt-4">
                        <QuizDeletionProgress
                          getDeletionStatus={(signal) =>
                            deletionClient.getDeletionStatus(
                              item.quizId,
                              deletionProgress.operationId,
                              signal,
                            )
                          }
                          initialStatus={deletionProgress.status}
                          onClose={() => setDeletionProgress(null)}
                          onTerminal={handleDeletionTerminal}
                        />
                      </div>
                    ) : null
                  }
                />
              ))}
            </ul>

            {paginationFailure !== null ? (
              <Alert
                className="mt-5"
                title={t("More quizzes could not be loaded")}
                variant="danger"
              >
                {libraryFailureMessage(paginationFailure, localize)}
              </Alert>
            ) : null}

            {nextCursor !== null ? (
              <Button
                className="mt-5"
                isLoading={isLoadingMore}
                loadingLabel={t("Loading more quizzes")}
                onClick={() => void loadMore()}
                variant="secondary"
              >
                {t("Load more")}
              </Button>
            ) : (
              <p className="mt-5 text-sm text-foreground-muted" role="status">
                {t("All quizzes loaded.")}
              </p>
            )}
          </div>
        )}
      </section>

      {deletionClient !== null && deletionDialog !== null ? (
        <QuizDeletionDialog
          authoritativeTitle={deletionDialog.title}
          client={deletionClient}
          onAccepted={handleDeletionAccepted}
          onClose={() => setDeletionDialog(null)}
          quizId={deletionDialog.quizId}
        />
      ) : null}
    </div>
  );
}

/**
 * FB-014: "Create quiz" opens a blank, deliberately UNSAVED authoring
 * workspace. No network call happens until the teacher's first explicit
 * Save; leaving without content creates no Quiz record.
 */
export function CreateQuizPage() {
  return <QuizAuthoringWorkspace quizId={null} />;
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
  return <QuizAuthoringWorkspace quizId={quizId} />;
}

/**
 * Unsaved → created state machine (FB-014).
 *
 * `quizId === null` is the blank unsaved workspace: no draft GET, no
 * createDraft POST, nothing persisted. The first explicit Save (gated on at
 * least one recognizable question from the shared client parser) fires ONE
 * createDraft with the full title/source payload, then router.replace's to
 * `/app/quizzes/{quizId}`. Remounting the persisted editor after create is
 * safe because the created draft is already server-persisted; later Saves
 * use the ordinary updateDraft path against that same quizId.
 */
function QuizAuthoringWorkspace({
  quizId,
}: {
  readonly quizId: string | null;
}) {
  const { localize, t } = useI18n();
  const localizeRef = useLocalizeRef();
  const router = useRouter();
  const client = useQuizApiClient();
  const deletionClient = useQuizDeletionApiClient();
  const editorRef = useRef<QuizMarkdownCodeEditorHandle>(null);
  const historyTriggerRef = useRef<HTMLButtonElement>(null);
  const deleteTriggerRef = useRef<HTMLButtonElement>(null);
  const pendingEditorFocusRef = useRef<PendingEditorFocus | null>(null);
  const [focusRequestVersion, requestEditorFocus] = useReducer(
    (version: number) => version + 1,
    0,
  );
  const [loadState, setLoadState] = useState<LoadState>(() =>
    quizId === null ? "ready" : "loading",
  );
  const [loadError, setLoadError] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [source, setSource] = useState("");
  const [savedSnapshot, setSavedSnapshot] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const saveInFlight = useRef(false);
  // The create transition has its own in-flight guard so rapid double Save
  // clicks can never fire a second createDraft while the first is pending.
  const createInFlight = useRef(false);
  const [firstSaveBlocked, setFirstSaveBlocked] = useState<string | null>(null);
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
  // FB-015 deletion state: the server-reported fence, the local fence for
  // operations started or observed in this session, and the followed
  // operation once the 202 arrives. `authoritativeTitle` is the last
  // server-persisted title (exact-title confirmation must never trust
  // unsaved editor input).
  const [serverDeletionStatus, setServerDeletionStatus] =
    useState<QuizDeletionStatus | null>(null);
  const [deletionPendingLocal, setDeletionPendingLocal] = useState(false);
  const [deletionDialogOpen, setDeletionDialogOpen] = useState(false);
  const [deletionOperation, setDeletionOperation] = useState<{
    readonly operationId: string;
    readonly status: string;
  } | null>(null);
  const [authoritativeTitle, setAuthoritativeTitle] = useState<string | null>(
    null,
  );
  const deletionFenced =
    isDeletionFenced(serverDeletionStatus) ||
    deletionPendingLocal ||
    deletionOperation !== null;

  const currentInput = useMemo<QuizDraftInput>(
    () => ({ authoringSource: source, description, title }),
    [description, source, title],
  );
  const latestInputRef = useRef(currentInput);
  useLayoutEffect(() => {
    latestInputRef.current = currentInput;
  }, [currentInput]);
  // An unsaved new quiz is "dirty" exactly when the teacher typed anything:
  // leaving an empty new-quiz screen needs no confirmation, while typed
  // content keeps the ordinary discard confirmation. A persisted draft keeps
  // the snapshot-difference semantics.
  const hasUnsavedNewContent =
    quizId === null &&
    (source.trim() !== "" || title.trim() !== "" || description.trim() !== "");
  const dirty =
    quizId === null
      ? hasUnsavedNewContent
      : savedSnapshot !== null && inputSnapshot(currentInput) !== savedSnapshot;

  // Reuse the shared unsaved-work guard so link navigation, reloads and
  // workspace switches (which navigate via the user menu) all offer the same
  // cancel-without-loss protection as the editor's Back control.
  const editorLeaveMessage = t(
    "You have unsaved changes. Leave the editor and discard them?",
  );
  useUnsavedNavigationGuard(dirty, editorLeaveMessage);

  function backToApp() {
    if (!dirty || window.confirm(editorLeaveMessage)) {
      router.push("/app/quizzes");
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
    if (client === null || quizId === null) {
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
        setLoadError(failureMessage(result.error, localizeRef.current));
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
      setAuthoritativeTitle(result.value.title);
      setServerDeletionStatus(result.value.deletionStatus);
      setLoadState("ready");
    });

    return () => controller.abort();
  }, [client, localizeRef, quizId]);

  async function saveDraft(): Promise<boolean> {
    if (client === null || saveInFlight.current) {
      return false;
    }
    const input = currentInput;

    if (quizId === null) {
      // FB-014 first-save gate: the draft must contain at least one
      // recognizable question per the existing client grammar (the same
      // analyzeQuizMarkdown the live preview uses). This is draft-level
      // gating only — publish-level completeness is still enforced later by
      // the authoritative backend publish validation.
      if (analyzeQuizMarkdown(input.authoringSource).questions.length === 0) {
        setFirstSaveBlocked(
          localize(
            "Add at least one recognizable question, for example {example}, then save again.",
            {
              example: "Câu 1 [SINGLE_CHOICE]:",
            },
          ),
        );
        return false;
      }
      if (createInFlight.current) {
        return false;
      }
      createInFlight.current = true;
      saveInFlight.current = true;
      setFirstSaveBlocked(null);
      setSaveError(null);
      setPublishMessage(null);
      setIsSaving(true);
      // IDENTITY GAP (documented, not client-worked-around): createDraft
      // posts WITHOUT an Idempotency-Key and the backend POST /api/quizzes
      // contract has no idempotency semantics (QuizController#create always
      // inserts), unlike Assessment create which carries an Idempotency-Key.
      // A retry after an ambiguous network failure may therefore create a
      // second quiz, so the failure copy below states that risk honestly
      // and a retry requires an explicit Save click — no automatic replay.
      // Smallest compatible backend addition: accept an optional
      // Idempotency-Key request header on POST /api/quizzes, persist its
      // hash with the Quiz row under a unique (owner, key) index, and on
      // conflict return the already-created draft (200) instead of
      // inserting a second Quiz.
      try {
        const result = await client.createDraft(input);
        if (!result.ok) {
          setSaveError(
            `${failureMessage(result.error, localize)} ${localize(
              "Quiz creation is not duplicate-protected yet. If the failed attempt reached the server, saving again may create a second quiz — check your library before retrying.",
            )}`,
          );
          return false;
        }
        setSavedSnapshot(inputSnapshot(input));
        router.replace(`/app/quizzes/${result.value.quizId}`);
        return true;
      } catch {
        setSaveError(
          `${localize(
            "Unable to save the draft. Check your connection and retry.",
          )} ${localize(
            "Quiz creation is not duplicate-protected yet. If the failed attempt reached the server, saving again may create a second quiz — check your library before retrying.",
          )}`,
        );
        return false;
      } finally {
        createInFlight.current = false;
        saveInFlight.current = false;
        setIsSaving(false);
      }
    }

    saveInFlight.current = true;
    setSaveError(null);
    setPublishMessage(null);
    setFirstSaveBlocked(null);
    setIsSaving(true);
    try {
      const result = await client.updateDraft(quizId, input);
      if (!result.ok) {
        if (isDeletionPendingFailure(result.error)) {
          setDeletionPendingLocal(true);
          setSaveError(
            localize(
              "This quiz is being deleted, so it can no longer be saved or published.",
            ),
          );
          return false;
        }
        setSaveError(failureMessage(result.error, localize));
        return false;
      }
      setSavedSnapshot(inputSnapshot(input));
      setAuthoritativeTitle(result.value.title);
      return true;
    } catch {
      setSaveError(
        localize("Unable to save the draft. Check your connection and retry."),
      );
      return false;
    } finally {
      saveInFlight.current = false;
      setIsSaving(false);
    }
  }

  async function publishDraft() {
    if (client === null || quizId === null || isPublishing || isSaving) {
      return;
    }
    setPublishError(null);
    setPublishMessage(null);
    setServerDiagnostics([]);
    setIsPublishing(true);

    const snapshotBeforePublish = inputSnapshot(latestInputRef.current);
    if (dirty && !(await saveDraft())) {
      setIsPublishing(false);
      return;
    }
    // A save can finish after the author has typed again. Never publish a
    // snapshot older than the visible editor state.
    if (inputSnapshot(latestInputRef.current) !== snapshotBeforePublish) {
      setPublishError(
        localize(
          "The draft changed while saving. Save your latest changes before publishing.",
        ),
      );
      setIsPublishing(false);
      return;
    }

    let result;
    try {
      result = await client.publishDraft(quizId);
    } catch {
      setPublishError(
        localize("Unable to publish. Check your connection and retry."),
      );
      setIsPublishing(false);
      return;
    }
    setIsPublishing(false);
    if (!result.ok) {
      if (result.error.kind === "validation-error") {
        setServerDiagnostics(result.error.errors);
        setPublishError(
          localize(
            "Quiz Markdown has errors. Select an item to jump to its source.",
          ),
        );
        return;
      }
      if (isDeletionPendingFailure(result.error)) {
        setDeletionPendingLocal(true);
        setPublishError(
          localize(
            "This quiz is being deleted, so it can no longer be saved or published.",
          ),
        );
        return;
      }
      setPublishError(failureMessage(result.error, localize));
      return;
    }
    setPublishMessage(
      result.value.created
        ? localize("Published version {version}.", {
            version: result.value.version.versionNumber,
          })
        : localize("Version {version} already matches this unchanged draft.", {
            version: result.value.version.versionNumber,
          }),
    );
    setHistoryRefreshVersion((current) => current + 1);
    setPublishDialogOpen(false);
  }

  function updateSource(value: string) {
    setSource(value);
    setServerDiagnostics([]);
    setPublishError(null);
    setPublishMessage(null);
    setFirstSaveBlocked(null);
  }

  function handleDeletionAccepted(accepted: QuizDeletionAccepted) {
    setDeletionDialogOpen(false);
    setDeletionOperation({
      operationId: accepted.operationId,
      status: accepted.status,
    });
    setSaveError(null);
    setPublishError(null);
    setPublishMessage(null);
    setFirstSaveBlocked(null);
  }

  function handleDeletionTerminal() {
    // The operation reached its terminal state; the editor's data is gone or
    // unrecoverable, so leave it for the library with an honest notice.
    writePendingDeletionNotice(title.trim() || localize("Untitled quiz"));
    router.push("/app/quizzes");
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
        <LoadingIndicator label={t("Loading quiz draft")} />
        <span className="text-sm font-medium text-foreground-secondary">
          {t("Loading quiz draft…")}
        </span>
      </div>
    );
  }

  if (loadState === "forbidden") {
    return (
      <Surface className="max-w-2xl p-6 sm:p-8">
        <h1 className="font-heading text-2xl font-normal text-foreground">
          {t("Quiz access denied")}
        </h1>
        <Alert
          className="mt-5"
          title={t("You cannot edit this quiz")}
          variant="danger"
        >
          {t("You do not have permission to edit this quiz.")}
        </Alert>
      </Surface>
    );
  }

  if (loadState === "not-found") {
    return (
      <Surface className="max-w-2xl p-6 sm:p-8">
        <h1 className="font-heading text-2xl font-normal text-foreground">
          {t("Quiz draft not found")}
        </h1>
        <p className="mt-3 text-base leading-7 text-foreground-secondary">
          {t("No draft was found for this Quiz ID.")}
        </p>
        <p className="mt-2 text-sm leading-6 text-foreground-muted">
          {t("If you just deleted this quiz, its removal has completed.")}
        </p>
        <Link className={`${linkButtonClasses} mt-6`} href="/app/quizzes">
          {t("Back to quiz authoring")}
        </Link>
      </Surface>
    );
  }

  if (loadState === "error") {
    return (
      <Surface className="max-w-2xl p-6 sm:p-8">
        <h1 className="font-heading text-2xl font-normal text-foreground">
          {t("Quiz could not be loaded")}
        </h1>
        <Alert className="mt-5" title={t("Loading failed")} variant="danger">
          {loadError ?? t("The quiz draft could not be loaded.")}
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
          aria-label={t("Back to Quiz Library")}
          className="flex min-h-11 shrink-0 items-center rounded-lg text-foreground hover:text-primary focus-visible:ring-2 focus-visible:ring-focus"
          href="/app/quizzes"
        >
          <QuizopiaLogo
            markClassName="size-9 rounded-lg"
            wordmarkClassName="hidden xl:flex"
          />
        </Link>
        <FocusedBackButton
          ariaLabel={t("Back to Quiz Library")}
          disabled={isSaving || isPublishing}
          onClick={backToApp}
        />
        <span
          aria-hidden="true"
          className="hidden h-7 w-px bg-border sm:block"
        />
        <div className="order-3 flex w-full min-w-0 items-center gap-3 sm:order-none sm:w-auto sm:min-w-[16rem] sm:flex-1 sm:max-w-xl">
          <label
            className="shrink-0 text-sm font-semibold text-foreground-secondary"
            htmlFor="editor-title"
          >
            {t("Quiz title")}
          </label>
          <input
            className="min-h-11 min-w-0 flex-1 rounded-lg border border-border-strong bg-surface px-3 py-2 text-base font-semibold text-foreground shadow-sm outline-none transition-colors hover:border-primary focus-visible:border-primary focus-visible:ring-2 focus-visible:ring-focus disabled:cursor-not-allowed disabled:bg-surface-muted disabled:text-foreground-muted"
            disabled={isSaving || isPublishing || deletionFenced}
            id="editor-title"
            onChange={(event) => {
              setTitle(event.target.value);
              setFirstSaveBlocked(null);
            }}
            placeholder={t("Untitled quiz")}
            value={title}
          />
        </div>
        <div className="order-4 ml-auto flex w-full flex-wrap items-center gap-2 sm:order-none sm:w-auto">
          {quizId !== null ? (
            <Button
              aria-expanded={historyOpen}
              aria-haspopup="dialog"
              className="px-3 sm:px-4"
              disabled={isSaving || isPublishing}
              onClick={() => setHistoryOpen(true)}
              ref={historyTriggerRef}
              variant="brand-outline"
            >
              {t("Published versions")}
            </Button>
          ) : null}
          <Button
            className="px-3 sm:px-4"
            disabled={
              (!dirty && !saveError) ||
              isSaving ||
              isPublishing ||
              deletionFenced
            }
            isLoading={isSaving}
            loadingLabel={t("Saving draft")}
            onClick={() => void saveDraft()}
            variant="neutral-outline"
          >
            <span
              aria-live="polite"
              className="inline-flex items-center gap-1.5"
            >
              {saveError ? (
                <RetryIcon className="size-4" />
              ) : quizId === null || dirty ? (
                <SaveIcon className="size-4" />
              ) : (
                <CheckIcon className="size-4" />
              )}
              {isSaving
                ? t("Saving…")
                : saveError
                  ? t("Retry save")
                  : quizId === null || dirty
                    ? t("Save")
                    : t("Saved")}
            </span>
          </Button>
          {quizId !== null && !deletionFenced ? (
            <Button
              className="shrink-0 whitespace-nowrap px-3 sm:px-4"
              disabled={isSaving || isPublishing}
              onClick={() => setDeletionDialogOpen(true)}
              ref={deleteTriggerRef}
              variant="danger-outline"
            >
              <span className="inline-flex items-center gap-1.5">
                <TrashIcon className="size-4" />
                {t("Delete quiz")}
              </span>
            </Button>
          ) : null}
          {quizId !== null ? (
            <Button
              className="px-3 sm:px-5"
              disabled={isSaving || deletionFenced}
              onClick={() => setPublishDialogOpen(true)}
            >
              {t("Publish")}
            </Button>
          ) : null}
        </div>
      </header>

      <div
        aria-hidden={historyOpen || undefined}
        className="flex min-h-0 flex-1 flex-col gap-2 p-2 sm:gap-3 sm:p-3"
        inert={historyOpen ? true : undefined}
      >
        {firstSaveBlocked ||
        saveError ||
        publishError ||
        publishMessage ||
        deletionFenced ? (
          <div className="shrink-0 space-y-2">
            {deletionFenced ? (
              <Alert title={t("This quiz is being deleted")} variant="warning">
                {t(
                  "Editing and publishing are disabled while the deletion is in progress.",
                )}
              </Alert>
            ) : null}
            {deletionOperation !== null && deletionClient !== null ? (
              <QuizDeletionProgress
                getDeletionStatus={(signal) =>
                  deletionClient.getDeletionStatus(
                    quizId as string,
                    deletionOperation.operationId,
                    signal,
                  )
                }
                initialStatus={deletionOperation.status}
                onTerminal={handleDeletionTerminal}
              />
            ) : null}
            {firstSaveBlocked ? (
              <Alert title={t("First save needs a question")} variant="warning">
                {firstSaveBlocked}
              </Alert>
            ) : null}
            {saveError ? (
              <Alert
                title={
                  quizId === null
                    ? t("Quiz could not be created")
                    : t("Draft was not saved")
                }
                variant="danger"
              >
                {saveError}
              </Alert>
            ) : null}
            {publishError ? (
              <Alert title={t("Quiz was not published")} variant="danger">
                {publishError}
              </Alert>
            ) : null}
            {publishMessage ? (
              <Alert title={t("Publish complete")} variant="success">
                {publishMessage}
              </Alert>
            ) : null}
          </div>
        ) : null}

        <div
          aria-label={t("Editor pane")}
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
              {pane === "source" ? t("Editor") : t("Preview")}
            </button>
          ))}
        </div>

        <div className="grid min-h-0 min-w-0 flex-1 gap-3 lg:grid-cols-2">
          <section
            aria-label={t("Quiz Markdown editor")}
            className={`${mobilePane === "source" ? "block" : "hidden"} min-h-0 min-w-0 overflow-hidden rounded-xl border border-primary/10 bg-surface p-3 shadow-card sm:p-4 lg:block`}
          >
            <QuizMarkdownCodeEditor
              disabled={isSaving || isPublishing || deletionFenced}
              onChange={updateSource}
              ref={editorRef}
              value={source}
            />
          </section>
          <section
            aria-label={t("Live quiz preview")}
            className={`${mobilePane === "preview" ? "block" : "hidden"} scrollbar-brand scrollbar-brand-gutter min-h-0 min-w-0 overflow-y-auto rounded-xl border border-primary/10 bg-surface-muted/50 p-3 shadow-card sm:p-4 lg:block`}
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

      {quizId !== null ? (
        <PublishedVersionHistory
          client={client}
          onCreateAssessment={(version) => {
            if (dirty && !window.confirm(editorLeaveMessage)) return;
            const query = new URLSearchParams({
              quizId: version.quizId,
              quizVersionId: version.id,
              versionNumber: String(version.versionNumber),
            });
            router.push(`/app/assessments/new?${query.toString()}`);
          }}
          isOpen={historyOpen}
          onClose={() => setHistoryOpen(false)}
          quizId={quizId}
          refreshVersion={historyRefreshVersion}
          triggerRef={historyTriggerRef}
        />
      ) : null}

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
              {t("Publish immutable QuizVersion")}
            </h2>
            <p className="mt-2 text-sm leading-6 text-foreground-secondary">
              {t(
                "This publishes quiz content only. Assessment timing, audience, and classroom delivery remain a separate product flow.",
              )}
            </p>
            <div className="mt-5">
              <label
                className="text-sm font-semibold text-foreground-secondary"
                htmlFor="publish-description"
              >
                {t("Description")}
              </label>
              <textarea
                autoFocus
                className="mt-2 min-h-28 w-full resize-y rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground outline-none focus:border-primary focus:ring-2 focus:ring-focus/30"
                disabled={isPublishing}
                id="publish-description"
                onChange={(event) => setDescription(event.target.value)}
                placeholder={t("Optional context saved with this quiz draft")}
                value={description}
              />
            </div>
            <div className="mt-6 flex flex-wrap justify-end gap-3">
              <Button
                disabled={isPublishing}
                onClick={() => setPublishDialogOpen(false)}
                variant="secondary"
              >
                {t("Cancel")}
              </Button>
              <Button
                isLoading={isPublishing}
                loadingLabel={t("Publishing quiz")}
                onClick={() => void publishDraft()}
              >
                {t("Publish QuizVersion")}
              </Button>
            </div>
          </Surface>
        </div>
      ) : null}

      {quizId !== null && deletionClient !== null && deletionDialogOpen ? (
        <QuizDeletionDialog
          authoritativeTitle={authoritativeTitle}
          client={deletionClient}
          onAccepted={handleDeletionAccepted}
          onClose={() => setDeletionDialogOpen(false)}
          quizId={quizId}
          triggerRef={deleteTriggerRef}
        />
      ) : null}
    </div>
  );
}
