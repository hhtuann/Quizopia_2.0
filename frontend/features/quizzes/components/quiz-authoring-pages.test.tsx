import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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
    login: vi.fn(),
    logout: vi.fn(async () => ({ ok: true as const, value: undefined })),
    register: vi.fn(),
    requestVerification: vi.fn(),
    runtime,
  };

  render(<AuthProvider service={service}>{children}</AuthProvider>);
  return { runtime };
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

  it("shows a truthful role state for an authenticated student without TEACHER", () => {
    renderAuthenticated(
      <QuizAuthoringLayout>
        <p>Authoring content</p>
      </QuizAuthoringLayout>,
      { roles: ["STUDENT"] },
    );
    expect(
      screen.getByRole("heading", { name: "Teacher access required" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("Authoring content")).not.toBeInTheDocument();
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
    expect(screen.getAllByText("2026-10-01T03:00:00Z")).toHaveLength(2);
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
    expect(
      screen.getByText(/could not reach the Quiz service/i),
    ).toBeInTheDocument();
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
      screen.getByText(
        "Your authenticated session is not available for this request.",
      ),
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
      screen.getByText(
        "The Quiz service returned an unexpected response (200).",
      ),
    ).toBeInTheDocument();
  });
});

describe("CreateQuizPage direct editor flow", () => {
  it("creates a minimal real backend draft and enters the focused editor route", async () => {
    let createBody: unknown = null;
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => {
        const built = request.createRequest();
        createBody = JSON.parse(String(built.body));
        return {
          kind: "response" as const,
          response: response(201, draft("")),
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

    expect(screen.queryByRole("form")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Description")).not.toBeInTheDocument();
    expect(screen.getByText("Preparing your editor…")).toBeInTheDocument();
    await waitFor(() =>
      expect(createBody).toEqual({
        authoringSource: "",
        description: "",
        title: "Untitled quiz",
      }),
    );
    expect(navigation.replace).toHaveBeenCalledWith(`/app/quizzes/${quizId}`);
    expect(executor.execute).toHaveBeenCalledTimes(1);
  });
});

describe("QuizEditorPage real-contract behavior", () => {
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
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
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
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
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

    expect(
      await screen.findByText("Published immutable version 1."),
    ).toBeInTheDocument();
    expect(publishCalls).toBe(1);
    expect(editor).not.toBeDisabled();
  });

  it("shows authoritative publish validation and maps it back to source", async () => {
    const source = "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 123";
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

    fireEvent.click(
      screen.getByRole("button", { name: /Jump to source for question 2/ }),
    );
    await waitFor(() => expect(editor).toHaveFocus());
    expect(editor.selectionStart).toBe(source.indexOf("Câu 2"));

    fireEvent.click(
      screen.getByRole("button", { name: "B. Not marked correct" }),
    );
    await waitFor(() =>
      expect(editor).toHaveValue(
        source.replace("*A. alpha", "A. alpha").replace("B. beta", "*B. beta"),
      ),
    );
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
  });
});
