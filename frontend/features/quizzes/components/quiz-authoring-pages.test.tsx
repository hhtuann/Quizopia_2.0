import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
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
import { QuizEditorPage } from "./quiz-authoring-pages";

const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const userId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";

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
      await screen.findByRole("heading", { name: "Network quiz" }),
    ).toBeInTheDocument();
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
    await screen.findByRole("heading", { name: "Network quiz" });

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
    await screen.findByRole("heading", { name: "Network quiz" });
    fireEvent.click(screen.getByRole("button", { name: "Publish version" }));

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
    await screen.findByRole("heading", { name: "Network quiz" });

    fireEvent.click(screen.getByRole("button", { name: "Publish version" }));

    expect(
      await screen.findByText(
        "Version 2 already matches this unchanged draft.",
      ),
    ).toBeInTheDocument();
  });
});
