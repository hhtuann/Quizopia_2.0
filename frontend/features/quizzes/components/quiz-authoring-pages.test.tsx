import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { StrictMode, type ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthenticatedRequestExecutor } from "../../../lib/api/authenticated-request";
import type { HttpResponse } from "../../../lib/api/http-transport";
import { AuthProvider } from "../../auth/auth-provider";
import {
  createAuthenticatedUser,
  type AuthRole,
} from "../../auth/model/authenticated-user";
import { createAccessTokenVault } from "../../auth/session/access-token-vault";
import type { AuthSessionService } from "../../auth/session/auth-session-service";
import { createSessionRuntime } from "../../auth/session/session-runtime";
import { QuizAuthoringLayout } from "./quiz-authoring-layout";
import {
  CreateQuizPage,
  QuizEditorPage,
  QuizLibraryPage,
} from "./quiz-authoring-pages";

const navigation = vi.hoisted(() => ({
  pathname: "/app/quizzes",
  push: vi.fn(),
  replace: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
  useRouter: () => navigation,
}));

const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const secondQuizId = "9bd4c564-3c27-4e6d-91aa-a004334aa8f7";
const userId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";

beforeEach(() => {
  navigation.pathname = "/app/quizzes";
  navigation.push.mockClear();
});

function response(status: number, value: unknown): HttpResponse {
  return {
    body: { kind: "json", value },
    headers: new Headers({ "content-type": "application/json" }),
    ok: status >= 200 && status < 300,
    status,
  };
}

function draft(authoringSource: string) {
  return {
    quizId,
    title: "Network quiz",
    description: "Quiz description",
    authoringSource,
    createdAt: "2026-09-30T12:00:00Z",
    updatedAt: "2026-09-30T12:00:01Z",
    deletionStatus: null,
  };
}

function libraryItem(
  id: string,
  title: string | null,
  latestVersionNumber: number | null,
) {
  return {
    quizId: id,
    title,
    description: title === null ? null : `${title} description`,
    createdAt: "2026-09-30T12:00:00Z",
    updatedAt: "2026-10-01T03:00:00Z",
    latestVersionNumber,
    deletionStatus: null,
  };
}

function renderAuthenticated(
  children: ReactNode,
  options: {
    readonly roles?: readonly AuthRole[];
    readonly executor?: AuthenticatedRequestExecutor;
  } = {},
) {
  const vault = createAccessTokenVault();
  const runtime = createSessionRuntime(vault);
  runtime.establishAuthenticatedSession({
    accessToken: "quiz-authoring-test-token",
    user: createAuthenticatedUser({
      email: "teacher@gmail.com",
      id: userId,
      roles: options.roles ?? ["TEACHER"],
      username: "teacher",
    }),
  });
  const executor =
    options.executor ??
    ({
      execute: vi.fn(),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    } satisfies AuthenticatedRequestExecutor);
  const service: AuthSessionService = {
    authenticatedRequests: executor,
    bootstrap: vi.fn(async () => ({ status: "no-session" as const })),
    confirmVerification: vi.fn(),
    enableTeacher: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(async () => ({ ok: true as const, value: undefined })),
    register: vi.fn(),
    requestVerification: vi.fn(),
    retryVerification: vi.fn(),
    readAccessToken: () => null,
    runtime,
  };

  render(<AuthProvider service={service}>{children}</AuthProvider>);
  return { runtime, service };
}

describe("Quiz teacher authoring access", () => {
  it("uses the focused full-viewport shell for editor routes", () => {
    navigation.pathname = `/app/quizzes/${quizId}`;
    renderAuthenticated(
      <QuizAuthoringLayout>
        <p>Focused authoring content</p>
      </QuizAuthoringLayout>,
      { roles: ["TEACHER"] },
    );

    const main = screen.getByRole("main");
    expect(main).toHaveClass("h-dvh", "overflow-hidden");
    expect(screen.getByText("Focused authoring content")).toBeInTheDocument();
    expect(screen.queryByRole("banner")).not.toBeInTheDocument();
  });

  it("offers teacher self-enablement for an authenticated student without TEACHER", async () => {
    const { runtime, service } = renderAuthenticated(
      <QuizAuthoringLayout>
        <p>Authoring content</p>
      </QuizAuthoringLayout>,
      { roles: ["STUDENT"] },
    );
    expect(
      screen.getByRole("heading", { name: "Teacher access required" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("Authoring content")).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Register as teacher" }),
    ).toBeEnabled();

    vi.mocked(service.enableTeacher).mockImplementationOnce(async () => {
      const authoritativeUser = createAuthenticatedUser({
        email: "teacher@gmail.com",
        id: userId,
        roles: ["STUDENT", "TEACHER"],
        username: "teacher",
      });
      runtime.beginRefreshing();
      runtime.completeRefreshing({
        accessToken: "teacher-enabled-access",
        user: authoritativeUser,
      });
      return { ok: true, value: authoritativeUser };
    });

    fireEvent.click(
      screen.getByRole("button", { name: "Register as teacher" }),
    );
    await waitFor(() =>
      expect(
        screen.getByRole("heading", { name: "Open the Teaching workspace" }),
      ).toBeInTheDocument(),
    );
    expect(screen.getByRole("status")).toHaveTextContent(
      "Teacher access is ready",
    );
    expect(runtime.getSnapshot()).toMatchObject({
      activeWorkspace: "LEARNING",
      user: { roles: ["STUDENT", "TEACHER"] },
    });
  });

  it("moves a teacher into the Teaching workspace without changing roles", () => {
    const { runtime } = renderAuthenticated(
      <QuizAuthoringLayout>
        <p>Authoring content</p>
      </QuizAuthoringLayout>,
      { roles: ["STUDENT", "TEACHER"] },
    );
    expect(
      screen.getByRole("heading", { name: "Open the Teaching workspace" }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Switch to Teaching" }));
    expect(screen.getByText("Authoring content")).toBeInTheDocument();
    expect(runtime.getSnapshot()).toMatchObject({
      activeWorkspace: "TEACHING",
      user: { roles: ["STUDENT", "TEACHER"] },
    });
  });
});

describe("QuizLibraryPage real listing contract", () => {
  it("shows an accessible loading state and then the true backend empty state", async () => {
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => {
        await gate;
        return {
          kind: "response" as const,
          response: response(200, { items: [], nextCursor: null }),
        };
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizLibraryPage />, { executor });

    expect(screen.getByRole("status")).toHaveTextContent(
      "Loading quiz library",
    );
    release?.();

    expect(
      await screen.findByRole("heading", { name: "No quizzes yet" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Create your first quiz" }),
    ).toHaveAttribute("href", "/app/quizzes/new");
  });

  it("renders multiple backend quizzes and links each real Quiz ID to the existing editor route", async () => {
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => ({
        kind: "response" as const,
        response: response(200, {
          items: [
            libraryItem(quizId, "Newest quiz", 3),
            libraryItem(secondQuizId, null, null),
          ],
          nextCursor: null,
        }),
      })),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizLibraryPage />, { executor });

    expect(
      await screen.findByRole("link", { name: "Newest quiz" }),
    ).toHaveAttribute("href", `/app/quizzes/${quizId}`);
    expect(screen.getByText("Newest quiz description")).toBeInTheDocument();
    expect(screen.getByText("Latest version 3")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Untitled quiz" })).toHaveAttribute(
      "href",
      `/app/quizzes/${secondQuizId}`,
    );
    expect(screen.getByText("Draft only")).toBeInTheDocument();
    expect(
      document.querySelectorAll('time[datetime="2026-10-01T03:00:00Z"]'),
    ).toHaveLength(2);
    expect(
      screen.queryByRole("button", { name: "Load more" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("All quizzes loaded.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create quiz" })).toHaveAttribute(
      "href",
      "/app/quizzes/new",
    );
  });

  it("appends the next opaque-cursor page without duplicating already loaded quizzes", async () => {
    let calls = 0;
    const targets: string[] = [];
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => {
        calls += 1;
        targets.push(String(request.createRequest().target));
        return {
          kind: "response" as const,
          response:
            calls === 1
              ? response(200, {
                  items: [libraryItem(quizId, "First quiz", 1)],
                  nextCursor: "opaque-next-token",
                })
              : response(200, {
                  items: [
                    libraryItem(quizId, "First quiz", 1),
                    libraryItem(secondQuizId, "Second quiz", 2),
                  ],
                  nextCursor: null,
                }),
        };
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizLibraryPage />, { executor });

    await screen.findByRole("link", { name: "First quiz" });
    fireEvent.click(screen.getByRole("button", { name: "Load more" }));

    expect(
      await screen.findByRole("link", { name: "Second quiz" }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "First quiz" })).toHaveLength(1);
    expect(targets).toEqual([
      "/api/quizzes",
      "/api/quizzes?cursor=opaque-next-token",
    ]);
    expect(
      screen.queryByRole("button", { name: "Load more" }),
    ).not.toBeInTheDocument();
  });

  it("prevents duplicate load-more requests while pagination is in flight", async () => {
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    let calls = 0;
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => {
        calls += 1;
        if (calls === 1) {
          return {
            kind: "response" as const,
            response: response(200, {
              items: [libraryItem(quizId, "First quiz", 1)],
              nextCursor: "opaque-next-token",
            }),
          };
        }
        await gate;
        return {
          kind: "response" as const,
          response: response(200, { items: [], nextCursor: null }),
        };
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizLibraryPage />, { executor });
    await screen.findByRole("link", { name: "First quiz" });

    const loadMore = screen.getByRole("button", { name: "Load more" });
    fireEvent.click(loadMore);
    fireEvent.click(loadMore);
    await waitFor(() => expect(calls).toBe(2));
    expect(
      screen.getByRole("button", { name: "Loading more quizzes" }),
    ).toBeDisabled();
    release?.();
    await screen.findByText("All quizzes loaded.");
    expect(calls).toBe(2);
  });

  it("keeps loaded quizzes usable when a next-page request fails", async () => {
    let calls = 0;
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => {
        calls += 1;
        return {
          kind: "response" as const,
          response:
            calls === 1
              ? response(200, {
                  items: [libraryItem(quizId, "Keep me", null)],
                  nextCursor: "opaque-next-token",
                })
              : response(500, {
                  code: "INTERNAL_ERROR",
                  message: "Library page failed",
                  status: 500,
                  path: "/api/quizzes",
                }),
        };
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizLibraryPage />, { executor });
    const existing = await screen.findByRole("link", { name: "Keep me" });

    fireEvent.click(screen.getByRole("button", { name: "Load more" }));

    expect(await screen.findByText("Library page failed")).toBeInTheDocument();
    expect(existing).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Load more" })).toBeEnabled();
  });

  it.each([
    [401, "UNAUTHENTICATED", "Quiz library authentication required"],
    [403, "ACCESS_DENIED", "Quiz library access denied"],
  ] as const)(
    "renders backend %s as an explicit library state",
    async (status, code, title) => {
      const executor: AuthenticatedRequestExecutor = {
        execute: vi.fn(async () => ({
          kind: "response" as const,
          response: response(status, {
            code,
            message: `Library ${status}`,
            status,
            path: "/api/quizzes",
          }),
        })),
        executeOnce: vi.fn(),
        executeOnceWithAccessToken: vi.fn(),
      };
      renderAuthenticated(<QuizLibraryPage />, { executor });

      expect(await screen.findByText(title)).toBeInTheDocument();
      expect(screen.getByText(`Library ${status}`)).toBeInTheDocument();
    },
  );

  it("renders a transport failure without inventing local library state", async () => {
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => ({
        kind: "transport-error" as const,
        error: { kind: "network" as const },
      })),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizLibraryPage />, { executor });

    expect(
      await screen.findByText("Quiz library could not be loaded"),
    ).toBeInTheDocument();
    expect(screen.getByText(/Quizopia could not connect/i)).toBeInTheDocument();
    expect(screen.queryByText("Draft only")).not.toBeInTheDocument();
  });

  it("renders an authentication executor failure explicitly", async () => {
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => ({
        kind: "authentication-failure" as const,
        reason: "no-session" as const,
      })),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizLibraryPage />, { executor });

    expect(
      await screen.findByText("Quiz library could not be loaded"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Your session has ended. Please sign in again."),
    ).toBeInTheDocument();
  });

  it("renders a malformed successful response as an unexpected backend response", async () => {
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => ({
        kind: "response" as const,
        response: response(200, { items: "not-an-array", nextCursor: null }),
      })),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizLibraryPage />, { executor });

    expect(
      await screen.findByText("Quiz library could not be loaded"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Quizopia got an unexpected response (200)."),
    ).toBeInTheDocument();
  });
});

describe("CreateQuizPage unsaved workspace (create on first save)", () => {
  const validSource = "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50";

  function stubExecutor(): AuthenticatedRequestExecutor {
    return {
      execute: vi.fn(),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
  }

  it("opens a blank unsaved workspace without any network call", async () => {
    const executor = stubExecutor();
    renderAuthenticated(
      <StrictMode>
        <CreateQuizPage />
      </StrictMode>,
      { executor },
    );

    const title = await screen.findByRole("textbox", { name: "Quiz title" });
    expect(title).toHaveValue("");
    expect(
      screen.getByRole("textbox", { name: "Quiz Markdown source" }),
    ).toHaveValue("");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(
      screen.queryByRole("button", { name: "Publish" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Published versions" }),
    ).not.toBeInTheDocument();
    // StrictMode double effects must not create: creation is only possible
    // through an explicit Save click.
    expect(executor.execute).not.toHaveBeenCalled();
  });

  it("leaves an empty new-quiz screen with no confirmation and no POST", () => {
    const confirm = vi.spyOn(window, "confirm");
    try {
      const executor = stubExecutor();
      renderAuthenticated(<CreateQuizPage />, { executor });

      fireEvent.click(
        screen.getByRole("button", { name: "Back to Quiz Library" }),
      );

      expect(navigation.push).toHaveBeenCalledExactlyOnceWith("/app/quizzes");
      expect(confirm).not.toHaveBeenCalled();
      expect(executor.execute).not.toHaveBeenCalled();
    } finally {
      confirm.mockRestore();
    }
  });

  it("confirms before discarding typed content on the unsaved workspace", () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    try {
      const executor = stubExecutor();
      renderAuthenticated(<CreateQuizPage />, { executor });
      const editor = screen.getByRole("textbox", {
        name: "Quiz Markdown source",
      });
      fireEvent.change(editor, { target: { value: validSource } });
      const back = screen.getByRole("button", {
        name: "Back to Quiz Library",
      });

      fireEvent.click(back);

      expect(confirm).toHaveBeenCalledOnce();
      expect(navigation.push).not.toHaveBeenCalled();
      confirm.mockReturnValue(true);
      fireEvent.click(back);
      expect(navigation.push).toHaveBeenCalledExactlyOnceWith("/app/quizzes");
      expect(executor.execute).not.toHaveBeenCalled();
    } finally {
      confirm.mockRestore();
    }
  });

  it("blocks the first save without a recognizable question, then creates the draft once", async () => {
    let createBody: unknown = null;
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => {
        const built = request.createRequest();
        createBody = JSON.parse(String(built.body));
        return {
          kind: "response" as const,
          response: response(
            201,
            draft((createBody as { authoringSource: string }).authoringSource),
          ),
        };
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    navigation.replace.mockClear();
    renderAuthenticated(
      <StrictMode>
        <CreateQuizPage />
      </StrictMode>,
      { executor },
    );

    const title = await screen.findByRole("textbox", { name: "Quiz title" });
    const editor = screen.getByRole("textbox", {
      name: "Quiz Markdown source",
    });
    // Title-only content is saveable-looking but has no recognizable
    // question: Save stays actionable and must not POST.
    fireEvent.change(title, { target: { value: "Network quiz" } });
    const save = screen.getByRole("button", { name: "Save" });
    expect(save).toBeEnabled();
    fireEvent.click(save);

    expect(
      await screen.findByText("First save needs a question"),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/for example Câu 1 \[SINGLE_CHOICE\]:, then save again/),
    ).toBeInTheDocument();
    expect(executor.execute).not.toHaveBeenCalled();

    fireEvent.change(editor, { target: { value: validSource } });
    expect(
      screen.queryByText("First save needs a question"),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(createBody).toEqual({
        authoringSource: validSource,
        description: "",
        title: "Network quiz",
      }),
    );
    expect(navigation.replace).toHaveBeenCalledExactlyOnceWith(
      `/app/quizzes/${quizId}`,
    );
    expect(executor.execute).toHaveBeenCalledTimes(1);
  });

  it("collapses rapid double Save clicks into a single createDraft POST", async () => {
    let releaseCreate: (() => void) | undefined;
    const createGate = new Promise<void>((resolve) => {
      releaseCreate = resolve;
    });
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => {
        await createGate;
        return {
          kind: "response" as const,
          response: response(201, draft(validSource)),
        };
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    navigation.replace.mockClear();
    renderAuthenticated(<CreateQuizPage />, { executor });
    const editor = await screen.findByRole("textbox", {
      name: "Quiz Markdown source",
    });
    fireEvent.change(editor, { target: { value: validSource } });

    const save = screen.getByRole("button", { name: "Save" });
    fireEvent.click(save);
    fireEvent.click(save);

    releaseCreate?.();
    await waitFor(() =>
      expect(navigation.replace).toHaveBeenCalledExactlyOnceWith(
        `/app/quizzes/${quizId}`,
      ),
    );
    expect(executor.execute).toHaveBeenCalledTimes(1);
  });

  it("surfaces create failure with the honest duplicate risk and no automatic retry", async () => {
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => ({
        kind: "response" as const,
        response: response(500, {
          code: "INTERNAL_ERROR",
          message: "Create persistence failed",
          status: 500,
          path: "/api/quizzes",
        }),
      })),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<CreateQuizPage />, { executor });
    const editor = await screen.findByRole("textbox", {
      name: "Quiz Markdown source",
    });
    fireEvent.change(editor, { target: { value: validSource } });

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Quiz could not be created",
    );
    expect(screen.getByText(/not duplicate-protected yet/)).toBeInTheDocument();
    expect(screen.getByText(/Create persistence failed/)).toBeInTheDocument();
    // No automatic replay: exactly one POST so far, and the editor content
    // survives for an explicit retry.
    await waitFor(() => expect(executor.execute).toHaveBeenCalledTimes(1));
    expect(editor).toHaveValue(validSource);
    expect(screen.getByRole("button", { name: "Retry save" })).toBeEnabled();

    fireEvent.click(screen.getByRole("button", { name: "Retry save" }));
    await waitFor(() => expect(executor.execute).toHaveBeenCalledTimes(2));
  });
});

describe("QuizEditorPage real-contract behavior", () => {
  it("shows a labeled, editable title and navigates back from a saved draft", async () => {
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => ({
        kind: "response" as const,
        response: response(
          200,
          draft("Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50"),
        ),
      })),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
    const title = await screen.findByRole("textbox", { name: "Quiz title" });
    const titleLabel = screen.getByText("Quiz title", { selector: "label" });
    expect(titleLabel).toBeVisible();
    expect(titleLabel).toHaveAttribute("for", "editor-title");
    expect(title).toHaveAttribute("id", "editor-title");
    expect(titleLabel.parentElement).toBe(title.parentElement);
    expect(title.parentElement).toHaveClass("flex", "items-center", "gap-3");
    expect(titleLabel.nextElementSibling).toBe(title);
    expect(title).toHaveClass(
      "border-border-strong",
      "bg-surface",
      "focus-visible:ring-2",
    );
    title.focus();
    expect(title).toHaveFocus();
    const back = screen.getByRole("button", { name: "Back to Quiz Library" });
    expect(back).toBeEnabled();
    expect(back).toHaveAttribute("aria-label", "Back to Quiz Library");
    expect(back).toHaveTextContent(/^\s*$/);
    expect(back.querySelector('svg[aria-hidden="true"] path')).not.toBeNull();
    expect(screen.getByRole("button", { name: "Saved" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Publish" })).toBeInTheDocument();
    fireEvent.click(back);
    expect(navigation.push).toHaveBeenCalledExactlyOnceWith("/app/quizzes");
  });

  it("confirms before discarding a dirty title, preserving changes when canceled", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    try {
      const executor: AuthenticatedRequestExecutor = {
        execute: vi.fn(async () => ({
          kind: "response" as const,
          response: response(
            200,
            draft("Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50"),
          ),
        })),
        executeOnce: vi.fn(),
        executeOnceWithAccessToken: vi.fn(),
      };
      renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
      const title = await screen.findByRole("textbox", { name: "Quiz title" });
      fireEvent.change(title, {
        target: { value: "A long changed quiz title" },
      });
      expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
      const back = screen.getByRole("button", { name: "Back to Quiz Library" });
      fireEvent.click(back);
      expect(confirm).toHaveBeenCalledOnce();
      expect(navigation.push).not.toHaveBeenCalled();
      expect(title).toHaveValue("A long changed quiz title");
      confirm.mockReturnValue(true);
      fireEvent.click(back);
      expect(navigation.push).toHaveBeenCalledExactlyOnceWith("/app/quizzes");
    } finally {
      confirm.mockRestore();
    }
  });

  it("loads and saves through the authenticated executor without rewriting source", async () => {
    let savedBody: unknown = null;
    const initialSource = "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50";
    const exactEditedSource =
      "\nCâu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50\nLời giải:  keep  spaces\n";
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => {
        const built = request.createRequest();
        if (built.method === "GET") {
          return {
            kind: "response" as const,
            response: response(200, draft(initialSource)),
          };
        }
        if (built.method === "PUT") {
          savedBody = JSON.parse(String(built.body));
          return {
            kind: "response" as const,
            response: response(
              200,
              draft((savedBody as { authoringSource: string }).authoringSource),
            ),
          };
        }
        throw new Error(`Unexpected method ${built.method}`);
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });

    expect(
      await screen.findByRole("textbox", { name: "Quiz title" }),
    ).toHaveValue("Network quiz");
    expect(
      screen.getByRole("region", { name: "Quiz Markdown editor" }),
    ).toHaveClass("overflow-hidden");
    expect(
      screen.getByRole("region", { name: "Live quiz preview" }),
    ).toHaveClass("overflow-y-auto");
    const editor = screen.getByRole("textbox", {
      name: "Quiz Markdown source",
    });
    fireEvent.change(editor, { target: { value: exactEditedSource } });
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(savedBody).toMatchObject({ authoringSource: exactEditedSource }),
    );
    expect((savedBody as { authoringSource: string }).authoringSource).toBe(
      exactEditedSource,
    );
    expect(await screen.findByText("Saved")).toBeInTheDocument();
  });

  it.each([
    [403, "ACCESS_DENIED", "Quiz access denied"],
    [404, "QUIZ_NOT_FOUND", "Quiz draft not found"],
  ] as const)(
    "renders backend %s as an explicit state",
    async (status, code, heading) => {
      const executor: AuthenticatedRequestExecutor = {
        execute: vi.fn(async (request) => ({
          kind: "response" as const,
          response: response(status, {
            code,
            message: status === 403 ? "Access is denied" : "Quiz was not found",
            status,
            path: `/api/quizzes/${quizId}/draft`,
          }),
        })),
        executeOnce: vi.fn(),
        executeOnceWithAccessToken: vi.fn(),
      };
      renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
      expect(
        await screen.findByRole("heading", { name: heading }),
      ).toBeInTheDocument();
    },
  );

  it("renders a backend 401 as an explicit load failure", async () => {
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => ({
        kind: "response" as const,
        response: response(401, {
          code: "UNAUTHENTICATED",
          message: "Authentication is required",
          status: 401,
          path: request.createRequest().target.toString(),
        }),
      })),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });

    expect(
      await screen.findByRole("heading", { name: "Quiz could not be loaded" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Authentication is required")).toBeInTheDocument();
  });

  it("keeps unsaved source intact when saving fails", async () => {
    const initialSource = "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50";
    const editedSource = `${initialSource}\nLời giải: keep this edit`;
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => {
        const built = request.createRequest();
        if (built.method === "GET") {
          return {
            kind: "response" as const,
            response: response(200, draft(initialSource)),
          };
        }
        if (built.method === "PUT") {
          return {
            kind: "response" as const,
            response: response(500, {
              code: "INTERNAL_ERROR",
              message: "Draft persistence failed",
              status: 500,
              path: `/api/quizzes/${quizId}/draft`,
            }),
          };
        }
        throw new Error(`Unexpected method ${built.method}`);
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
    await screen.findByRole("textbox", { name: "Quiz title" });

    const editor = screen.getByRole("textbox", {
      name: "Quiz Markdown source",
    });
    fireEvent.change(editor, { target: { value: editedSource } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(
      await screen.findByText("Draft persistence failed"),
    ).toBeInTheDocument();
    expect(editor).toHaveValue(editedSource);
    expect(screen.getByRole("button", { name: "Retry save" })).toBeEnabled();
  });

  it("keeps unsaved draft state isolated while previewing a published version", async () => {
    const initialSource = "Câu 1 [NUMERIC_FILL]: original?\nĐáp án: 2.50";
    const unsavedSource = `${initialSource}\nLời giải: unsaved current-draft edit`;
    let updateCalls = 0;
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => {
        const built = request.createRequest();
        const target = String(built.target);
        if (target.endsWith("/draft") && built.method === "GET") {
          return {
            kind: "response" as const,
            response: response(200, draft(initialSource)),
          };
        }
        if (target.includes("/versions?")) {
          return {
            kind: "response" as const,
            response: response(200, {
              items: [
                {
                  id: "f87b6d86-c26d-47fe-83db-cce701112233",
                  quizId,
                  versionNumber: 1,
                  titleSnapshot: "Published title",
                  descriptionSnapshot: "Historical description",
                  contentSchemaVersion: 1,
                  createdAt: "2026-10-08T08:30:00Z",
                },
              ],
              nextCursor: null,
            }),
          };
        }
        if (target.endsWith("/versions/1")) {
          return {
            kind: "response" as const,
            response: response(200, {
              id: "f87b6d86-c26d-47fe-83db-cce701112233",
              quizId,
              versionNumber: 1,
              titleSnapshot: "Published title",
              descriptionSnapshot: "Historical description",
              sourceSnapshot: "Câu 1 [NUMERIC_FILL]: historical?\nĐáp án: 1.00",
              structuredContent: {
                questions: [
                  {
                    number: 1,
                    type: "NUMERIC_FILL",
                    stemMarkdown: "historical?",
                    options: [],
                    numericAnswer: "1.00",
                    explanationMarkdown: null,
                  },
                ],
              },
              contentSchemaVersion: 1,
              createdAt: "2026-10-08T08:30:00Z",
            }),
          };
        }
        if (built.method === "PUT") {
          updateCalls += 1;
        }
        throw new Error(`Unexpected request ${built.method} ${target}`);
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
    const editor = await screen.findByRole("textbox", {
      name: "Quiz Markdown source",
    });
    const titleInput = screen.getByRole("textbox", { name: "Quiz title" });
    fireEvent.change(editor, { target: { value: unsavedSource } });
    fireEvent.change(titleInput, {
      target: { value: "Unsaved current title" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Published versions" }));
    expect(
      screen.queryByRole("button", { name: "Save" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Publish" }),
    ).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: /Version 1/ }));
    expect(
      await screen.findByRole("heading", { name: "Published title" }),
    ).toBeInTheDocument();
    expect(screen.getByText("historical?")).toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "Close published versions" }),
    );

    expect(editor).toHaveValue(unsavedSource);
    expect(titleInput).toHaveValue("Unsaved current title");
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
    expect(updateCalls).toBe(0);
  });

  it("locks authoring while a dirty draft is saved before publish", async () => {
    const initialSource = "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50";
    const editedSource = `${initialSource}\nLời giải: publish this snapshot`;
    let releaseSave: (() => void) | undefined;
    const saveGate = new Promise<void>((resolve) => {
      releaseSave = resolve;
    });
    let publishCalls = 0;
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => {
        const built = request.createRequest();
        if (built.method === "GET") {
          return {
            kind: "response" as const,
            response: response(200, draft(initialSource)),
          };
        }
        if (built.method === "PUT") {
          await saveGate;
          return {
            kind: "response" as const,
            response: response(200, draft(editedSource)),
          };
        }
        if (built.method === "POST") {
          publishCalls += 1;
          return {
            kind: "response" as const,
            response: response(201, {
              id: "f87b6d86-c26d-47fe-83db-cce701112233",
              quizId,
              versionNumber: 1,
              createdAt: "2026-09-30T12:10:00Z",
            }),
          };
        }
        throw new Error(`Unexpected method ${built.method}`);
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
    await screen.findByRole("textbox", { name: "Quiz title" });

    const editor = screen.getByRole("textbox", {
      name: "Quiz Markdown source",
    });
    fireEvent.change(editor, { target: { value: editedSource } });
    fireEvent.click(screen.getByRole("button", { name: "Publish" }));
    expect(
      screen.getByRole("heading", { name: "Publish immutable QuizVersion" }),
    ).toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "Publish QuizVersion" }),
    );

    await waitFor(() => expect(editor).toBeDisabled());
    expect(screen.getByRole("textbox", { name: "Quiz title" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Description" })).toBeDisabled();
    expect(publishCalls).toBe(0);

    releaseSave?.();

    expect(await screen.findByText("Published version 1.")).toBeInTheDocument();
    expect(publishCalls).toBe(1);
    expect(editor).not.toBeDisabled();
  });

  it("shows authoritative publish validation and maps it back to source", async () => {
    const source = "Câu 1 [NUMERIC_FILL]: value?\r\nĐáp án: 123";
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => {
        const built = request.createRequest();
        if (built.method === "GET") {
          return {
            kind: "response" as const,
            response: response(200, draft(source)),
          };
        }
        if (built.method === "POST") {
          return {
            kind: "response" as const,
            response: response(400, {
              code: "QUIZ_MARKDOWN_INVALID",
              message: "Quiz Markdown validation failed.",
              status: 400,
              path: `/api/quizzes/${quizId}/versions`,
              traceId: "trace-123",
              errors: [
                {
                  code: "NUMERIC_ANSWER_INVALID",
                  questionNumber: 1,
                  line: 2,
                  column: 9,
                  message: "Numeric answer is invalid",
                },
              ],
            }),
          };
        }
        throw new Error(`Unexpected method ${built.method}`);
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
    await screen.findByRole("textbox", { name: "Quiz title" });
    fireEvent.click(screen.getByRole("button", { name: "Publish" }));
    fireEvent.click(
      screen.getByRole("button", { name: "Publish QuizVersion" }),
    );

    expect(
      await screen.findByRole("heading", { name: "Publish validation" }),
    ).toBeInTheDocument();
    expect(screen.getByText("NUMERIC_ANSWER_INVALID")).toBeInTheDocument();
    const diagnostic = screen.getByRole("button", {
      name: /NUMERIC_ANSWER_INVALID/,
    });
    fireEvent.click(diagnostic);
    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: "Quiz Markdown source" }),
      ).toHaveFocus(),
    );
    const editor = screen.getByRole<HTMLTextAreaElement>("textbox", {
      name: "Quiz Markdown source",
    });
    const sourceOffset = source.indexOf("Đáp án:") + 8;
    const textareaOffset = source
      .slice(0, sourceOffset)
      .replace(/\r\n|\r/g, "\n").length;
    expect(editor.selectionStart).toBe(textareaOffset);
    expect(editor.selectionEnd).toBe(textareaOffset);
  });

  it("reports backend 200 publish reuse without faking a new version", async () => {
    const source = "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50";
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => {
        const built = request.createRequest();
        if (built.method === "GET") {
          return {
            kind: "response" as const,
            response: response(200, draft(source)),
          };
        }
        if (built.method === "POST") {
          return {
            kind: "response" as const,
            response: response(200, {
              id: "f87b6d86-c26d-47fe-83db-cce701112233",
              quizId,
              versionNumber: 2,
              createdAt: "2026-09-30T12:10:00Z",
            }),
          };
        }
        throw new Error(`Unexpected method ${built.method}`);
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
    await screen.findByRole("textbox", { name: "Quiz title" });

    fireEvent.click(screen.getByRole("button", { name: "Publish" }));
    fireEvent.click(
      screen.getByRole("button", { name: "Publish QuizVersion" }),
    );

    expect(
      await screen.findByText(
        "Version 2 already matches this unchanged draft.",
      ),
    ).toBeInTheDocument();
  });

  it("moves between preview and exact source and minimally edits correctness", async () => {
    const source =
      "Câu 1 [SINGLE_CHOICE]: first\nline two\n*A. alpha\ncontinued\nB. beta\nC. gamma\nD. delta\n\nCâu 2 [NUMERIC_FILL]: value\nĐáp án: 2.50";
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async () => ({
        kind: "response" as const,
        response: response(200, draft(source)),
      })),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
    const editor = await screen.findByRole<HTMLTextAreaElement>("textbox", {
      name: "Quiz Markdown source",
    });
    expect(
      screen.getByRole("link", { name: "Back to Quiz Library" }),
    ).toContainElement(screen.getByTestId("quizopia-brand-mark"));

    fireEvent.click(
      screen.getByRole("button", { name: /Jump to source for question 2/ }),
    );
    await waitFor(() => expect(editor).toHaveFocus());
    expect(editor.selectionStart).toBe(source.indexOf("Câu 2"));

    fireEvent.click(
      screen.getByRole("button", { name: "B. Not marked correct" }),
    );
    const updatedSource = source
      .replace("*A. alpha", "A. alpha")
      .replace("B. beta", "*B. beta");
    await waitFor(() => expect(editor).toHaveValue(updatedSource));
    expect(editor.selectionStart).toBe(
      updatedSource.indexOf("*B. beta") + "*B. ".length,
    );
    expect(
      screen.queryByRole("listbox", { name: "Quiz Markdown suggestions" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();

    const markerCaret = updatedSource.indexOf("*B. beta") + 2;
    editor.setSelectionRange(markerCaret, markerCaret);
    fireEvent.click(editor);
    // Scope to the suggestion listbox: the header language control also
    // renders <option> elements for locale choices.
    expect(
      within(screen.getByRole("listbox", { name: "Quiz Markdown suggestions" }))
        .getAllByRole("option")
        .map((item) => item.textContent),
    ).toEqual(["B.", "*B."]);
    fireEvent.keyDown(editor, { key: "Escape" });

    const typedSource = `${updatedSource}\nc`;
    fireEvent.change(editor, {
      target: {
        selectionEnd: typedSource.length,
        selectionStart: typedSource.length,
        value: typedSource,
      },
    });
    expect(
      screen.getByRole("listbox", { name: "Quiz Markdown suggestions" }),
    ).toBeInTheDocument();
  });

  it.each([
    {
      action: "A. Not marked correct",
      content: "alpha",
      expected:
        "Câu 1 [SINGLE_CHOICE]: choose\n*A. alpha\nB. beta\nC. gamma\nD. delta",
      marker: "*A. ",
      name: "SINGLE_CHOICE B to A",
      source:
        "Câu 1 [SINGLE_CHOICE]: choose\nA. alpha\n*B. beta\nC. gamma\nD. delta",
    },
    {
      action: "B. Not marked correct",
      content: "beta",
      expected:
        "Câu 1 [MULTIPLE_CHOICE]: choose\n*A. alpha\n*B. beta\nC. gamma\nD. delta",
      marker: "*B. ",
      name: "MULTIPLE_CHOICE add",
      source:
        "Câu 1 [MULTIPLE_CHOICE]: choose\n*A. alpha\nB. beta\nC. gamma\nD. delta",
    },
    {
      action: "B. Marked correct",
      content: "beta",
      expected:
        "Câu 1 [MULTIPLE_CHOICE]: choose\n*A. alpha\nB. beta\nC. gamma\nD. delta",
      marker: "B. ",
      name: "MULTIPLE_CHOICE remove",
      source:
        "Câu 1 [MULTIPLE_CHOICE]: choose\n*A. alpha\n*B. beta\nC. gamma\nD. delta",
    },
    {
      action: "A. Not marked correct",
      content: "alpha",
      expected:
        "Câu 1 [TRUE_FALSE_MATRIX]: choose\r\n*A. alpha\r\n*B. beta\r\nC. gamma\r\nD. delta",
      marker: "*A. ",
      name: "TRUE_FALSE_MATRIX CRLF add",
      source:
        "Câu 1 [TRUE_FALSE_MATRIX]: choose\r\nA. alpha\r\n*B. beta\r\nC. gamma\r\nD. delta",
    },
  ])(
    "places the final caret from post-edit source for $name",
    async ({ action, content, expected, marker, source }) => {
      const executor: AuthenticatedRequestExecutor = {
        execute: vi.fn(async () => ({
          kind: "response" as const,
          response: response(200, draft(source)),
        })),
        executeOnce: vi.fn(),
        executeOnceWithAccessToken: vi.fn(),
      };
      renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
      const editor = await screen.findByRole<HTMLTextAreaElement>("textbox", {
        name: "Quiz Markdown source",
      });

      fireEvent.click(screen.getByRole("button", { name: action }));
      await waitFor(() =>
        expect(editor).toHaveValue(expected.replace(/\r\n|\r/g, "\n")),
      );
      const sourceCaret =
        expected.indexOf(`${marker}${content}`) + marker.length;
      const textareaCaret = expected
        .slice(0, sourceCaret)
        .replace(/\r\n|\r/g, "\n").length;
      expect(editor.selectionStart).toBe(textareaCaret);
      expect(editor.selectionEnd).toBe(textareaCaret);
      expect(
        screen.queryByRole("listbox", { name: "Quiz Markdown suggestions" }),
      ).not.toBeInTheDocument();
    },
  );
});

const deletionOperationId = "1c9d0d7e-5c8a-4b8f-9a55-1d3a6d3a2a11";

/** Route-aware executor: answers by (method, target) for deletion flows. */
function deletionFlowExecutor(options: {
  readonly draftDeletionStatus?: string | null;
  readonly libraryDeletionStatus?: string | null;
  readonly acceptedStatus?: string;
  readonly statusSequence?: readonly string[];
}) {
  const requests: Array<{
    readonly method?: string;
    readonly target: string;
    readonly headers: Headers;
    readonly body?: string;
  }> = [];
  const executor: AuthenticatedRequestExecutor = {
    execute: vi.fn(async (request) => {
      const built = request.createRequest();
      const headers = new Headers(built.headers);
      const target = String(built.target);
      requests.push({
        method: built.method,
        target,
        headers,
        body: typeof built.body === "string" ? built.body : undefined,
      });
      if (
        built.method === "POST" &&
        target === `/api/quizzes/${quizId}/deletion-requests`
      ) {
        return {
          kind: "response" as const,
          response: response(202, {
            operationId: deletionOperationId,
            status: options.acceptedStatus ?? "QUIZ_FENCED",
          }),
        };
      }
      if (
        built.method === "GET" &&
        target ===
          `/api/quizzes/${quizId}/deletion-requests/${deletionOperationId}`
      ) {
        const polls = requests.filter(
          (seen) => seen.method === "GET" && seen.target === target,
        ).length;
        const sequence = options.statusSequence ?? [
          options.acceptedStatus ?? "QUIZ_FENCED",
        ];
        const state =
          sequence[Math.min(polls - 1, sequence.length - 1)] ?? "QUIZ_FENCED";
        const terminal = state === "PRIMARY_PURGED";
        return {
          kind: "response" as const,
          response: response(200, {
            operationId: deletionOperationId,
            status: state,
            createdAt: "2026-10-20T08:00:00Z",
            updatedAt: "2026-10-20T08:01:00Z",
            lastReason: null,
            stages: ["REQUESTED"],
            ...(terminal ? { note: "COPY_EXPIRY_PENDING" } : {}),
          }),
        };
      }
      if (built.method === "GET" && target === "/api/quizzes") {
        return {
          kind: "response" as const,
          response: response(200, {
            items: [
              {
                ...libraryItem(quizId, "Network quiz", 2),
                deletionStatus: options.libraryDeletionStatus ?? null,
              },
            ],
            nextCursor: null,
          }),
        };
      }
      // Draft reads/writes.
      if (target === `/api/quizzes/${quizId}/draft`) {
        return {
          kind: "response" as const,
          response: response(200, {
            ...draft("Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50"),
            deletionStatus: options.draftDeletionStatus ?? null,
          }),
        };
      }
      return {
        kind: "response" as const,
        response: response(404, {
          code: "NOT_MOCKED",
          message: `No fixture for ${built.method} ${target}`,
          status: 404,
          path: target,
        }),
      };
    }),
    executeOnce: vi.fn(),
    executeOnceWithAccessToken: vi.fn(),
  };
  return { executor, requests };
}

/**
 * Uses only synchronous queries: under faked setTimeout, RTL's async
 * findBy* helpers never resolve (their wrapper awaits setTimeout(0)).
 */
function openDeletionDialogAndConfirm(title = "Network quiz") {
  fireEvent.click(screen.getByRole("button", { name: "Delete quiz" }));
  const input = screen.getByLabelText("Type the exact title to confirm");
  fireEvent.change(input, { target: { value: title } });
  fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));
}

describe("Quiz deletion UX (FB-015)", () => {
  it("offers a destructive owner-only delete action per library card", async () => {
    const { executor } = deletionFlowExecutor({});
    renderAuthenticated(<QuizLibraryPage />, { executor });

    expect(
      await screen.findByRole("button", { name: "Delete quiz" }),
    ).toBeInTheDocument();
  });

  it("replaces the library delete action with a Being deleted state while fenced", async () => {
    const { executor } = deletionFlowExecutor({
      libraryDeletionStatus: "QUIZ_FENCED",
    });
    renderAuthenticated(<QuizLibraryPage />, { executor });

    expect(await screen.findByText("Being deleted")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Delete quiz" }),
    ).not.toBeInTheDocument();
  });

  it("runs the full honest library flow: confirm, follow progress, terminal notice, reload", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    try {
      const { executor, requests } = deletionFlowExecutor({
        acceptedStatus: "QUIZ_FENCED",
        statusSequence: ["ACCESS_REVOKED", "PRIMARY_PURGED"],
      });
      renderAuthenticated(<QuizLibraryPage />, { executor });

      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      openDeletionDialogAndConfirm();

      // The immediate first poll reports ACCESS_REVOKED.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      expect(
        screen.getByText("Access blocked · Deleting data"),
      ).toBeInTheDocument();

      // The request carried a canonical Idempotency-Key header.
      const post = requests.find(
        (seen) =>
          seen.method === "POST" && seen.target.endsWith("/deletion-requests"),
      );
      expect(post?.headers.get("Idempotency-Key") ?? "").toMatch(
        /^[A-Za-z0-9_-]{16,128}$/,
      );

      // Next bounded poll reaches the terminal state: the card's panel is
      // replaced by the success notice and the library reloads.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3000);
      });
      expect(screen.queryByText("Fully deleted")).not.toBeInTheDocument();
      expect(screen.getByText("Quiz deleted")).toBeInTheDocument();
      expect(
        screen.getByText(
          "“Network quiz” was deleted. Students can no longer access it.",
        ),
      ).toBeInTheDocument();
      expect(
        requests.filter(
          (seen) => seen.method === "GET" && seen.target === "/api/quizzes",
        ).length,
      ).toBeGreaterThanOrEqual(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("keeps the delete entry out of a fenced editor and disables edit affordances with a banner", async () => {
    const { executor } = deletionFlowExecutor({
      draftDeletionStatus: "QUIZ_FENCED",
    });
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });

    const title = await screen.findByLabelText("Quiz title");
    await screen.findByRole("textbox", { name: "Quiz Markdown source" });

    expect(title).toBeDisabled();
    expect(screen.getByRole("button", { name: "Saved" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Publish" })).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Published versions" }),
    ).toBeEnabled();
    expect(
      screen.queryByRole("button", { name: "Delete quiz" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("This quiz is being deleted")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Editing and publishing are disabled while the deletion is in progress.",
      ),
    ).toBeInTheDocument();
  });

  it("answers a concurrent QUIZ_DELETION_PENDING save with honest fenced copy", async () => {
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => {
        const built = request.createRequest();
        if (built.method === "GET") {
          return {
            kind: "response" as const,
            response: response(
              200,
              draft("Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50"),
            ),
          };
        }
        return {
          kind: "response" as const,
          response: response(409, {
            code: "QUIZ_DELETION_PENDING",
            message: "Deletion pending",
            status: 409,
            path: String(built.target),
          }),
        };
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
    await screen.findByRole("textbox", { name: "Quiz Markdown source" });

    const editor = screen.getByRole<HTMLTextAreaElement>("textbox", {
      name: "Quiz Markdown source",
    });
    fireEvent.change(editor, {
      target: { value: "Câu 1 [NUMERIC_FILL]: x?\nĐáp án: 1" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(
      await screen.findByText(
        "This quiz is being deleted, so it can no longer be saved or published.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("This quiz is being deleted")).toBeInTheDocument();
    // The local fence now also disables further edit attempts.
    expect(screen.getByRole("button", { name: "Retry save" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Publish" })).toBeDisabled();
  });

  it("deletes from the editor, follows progress, and lands on the library with a notice", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    try {
      sessionStorage.clear();
      const { executor } = deletionFlowExecutor({
        acceptedStatus: "REQUESTED",
        statusSequence: ["ACCESS_REVOKED", "PRIMARY_PURGED"],
      });
      renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      expect(
        screen.getByRole("textbox", { name: "Quiz Markdown source" }),
      ).toBeInTheDocument();

      openDeletionDialogAndConfirm();

      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      expect(
        screen.getByText("Access blocked · Deleting data"),
      ).toBeInTheDocument();
      expect(
        screen.getByText("This quiz is being deleted"),
      ).toBeInTheDocument();

      // Terminal on the first bounded poll, honestly including the copies note.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3000);
      });
      expect(
        screen.getByText(
          "Primary data deleted · Waiting for backup copies to be erased",
        ),
      ).toBeInTheDocument();
      expect(screen.queryByText("Fully deleted")).not.toBeInTheDocument();
      expect(navigation.push).toHaveBeenCalledWith("/app/quizzes");
      expect(sessionStorage.getItem("quizopia.quiz-deletion.notice")).toContain(
        "Network quiz",
      );
    } finally {
      vi.useRealTimers();
      sessionStorage.clear();
    }
  });

  it("shows the cross-navigation deletion notice once on the library", async () => {
    sessionStorage.clear();
    sessionStorage.setItem(
      "quizopia.quiz-deletion.notice",
      JSON.stringify({ title: "Network quiz" }),
    );
    const { executor } = deletionFlowExecutor({});
    renderAuthenticated(<QuizLibraryPage />, { executor });

    expect(
      await screen.findByText(
        "“Network quiz” was deleted. Students can no longer access it.",
      ),
    ).toBeInTheDocument();
    // Read once: the storage entry is consumed.
    expect(sessionStorage.getItem("quizopia.quiz-deletion.notice")).toBeNull();
  });
});
