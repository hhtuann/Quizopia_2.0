import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import type { AuthenticatedRequestExecutor } from "../../../lib/api/authenticated-request";
import type { HttpResponse } from "../../../lib/api/http-transport";
import { I18nProvider } from "../../../lib/i18n/provider";
import { AuthProvider } from "../../auth/auth-provider";
import { createAuthenticatedUser } from "../../auth/model/authenticated-user";
import { createAccessTokenVault } from "../../auth/session/access-token-vault";
import type { AuthSessionService } from "../../auth/session/auth-session-service";
import { createSessionRuntime } from "../../auth/session/session-runtime";
import { QuizAuthoringLayout } from "./quiz-authoring-layout";
import { QuizEditorPage, QuizLibraryPage } from "./quiz-authoring-pages";

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
    deletionStatus: null,
  };
}

function renderAuthenticated(
  children: ReactNode,
  options: {
    readonly executor: AuthenticatedRequestExecutor;
    readonly locale?: "vi" | "en";
    readonly roles?: readonly ("STUDENT" | "TEACHER")[];
  },
) {
  const vault = createAccessTokenVault();
  const runtime = createSessionRuntime(vault);
  runtime.establishAuthenticatedSession({
    accessToken: "quiz-i18n-test-token",
    user: createAuthenticatedUser({
      email: "teacher@gmail.com",
      id: userId,
      roles: options.roles ?? ["TEACHER"],
      username: "teacher",
    }),
  });
  const service: AuthSessionService = {
    authenticatedRequests: options.executor,
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

  render(
    <I18nProvider initialLocale={options.locale ?? "vi"}>
      <AuthProvider service={service}>{children}</AuthProvider>
    </I18nProvider>,
  );
}

function staticExecutor(value: unknown): AuthenticatedRequestExecutor {
  return {
    execute: vi.fn(async () => ({
      kind: "response" as const,
      response: response(200, value),
    })),
    executeOnce: vi.fn(),
    executeOnceWithAccessToken: vi.fn(),
  };
}

async function switchLocale(value: "en" | "vi") {
  fireEvent.change(screen.getByRole("combobox"), {
    target: { value },
  });
}

describe("quiz authoring bilingual presentation", () => {
  it("renders the Quiz Library and gate copy in Vietnamese by default", async () => {
    const executor = staticExecutor({
      items: [
        {
          quizId,
          title: "Newest quiz",
          description: null,
          createdAt: "2026-09-30T12:00:00Z",
          updatedAt: "2026-10-01T03:00:00Z",
          latestVersionNumber: 3,
          deletionStatus: null,
        },
        {
          quizId: "9bd4c564-3c27-4e6d-91aa-a004334aa8f7",
          title: null,
          description: null,
          createdAt: "2026-09-30T12:00:00Z",
          updatedAt: "2026-10-01T03:00:00Z",
          latestVersionNumber: null,
          deletionStatus: null,
        },
      ],
      nextCursor: null,
    });
    renderAuthenticated(<QuizLibraryPage />, { executor, locale: "vi" });

    expect(
      await screen.findByRole("heading", { name: "Soạn câu hỏi" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Tạo bộ câu hỏi" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Phiên bản mới nhất 3")).toBeInTheDocument();
    expect(screen.getByText("Chỉ bản nháp")).toBeInTheDocument();
    expect(screen.getByText(`Mã bộ câu hỏi: ${quizId}`)).toBeInTheDocument();
    expect(screen.getByText("Đã tải toàn bộ bộ câu hỏi.")).toBeInTheDocument();
    // Raw teacher title values stay verbatim.
    expect(screen.getByRole("link", { name: "Newest quiz" })).toHaveAttribute(
      "href",
      `/app/quizzes/${quizId}`,
    );
  });

  it("shows the teacher-enablement gate in Vietnamese for non-teacher accounts", () => {
    renderAuthenticated(
      <QuizAuthoringLayout>
        <p>Authoring content</p>
      </QuizAuthoringLayout>,
      {
        executor: staticExecutor({ items: [], nextCursor: null }),
        roles: ["STUDENT"],
      },
    );

    expect(
      screen.getByRole("heading", { name: "Cần quyền giáo viên" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Đăng ký làm giáo viên để soạn bộ câu hỏi"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Đăng ký làm giáo viên" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("Authoring content")).not.toBeInTheDocument();
  });

  it("switching the editor locale to English preserves draft text, selection and component identity", async () => {
    const source =
      "Câu 1 [SINGLE_CHOICE]: giữ nguyên văn bản\n*A. HTTP\nB. TCP";
    const executor = staticExecutor(draft(source));
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });

    const editor = (await screen.findByRole("textbox", {
      name: "Mã nguồn Quiz Markdown",
    })) as HTMLTextAreaElement;
    const titleInput = screen.getByRole("textbox", {
      name: "Tiêu đề bộ câu hỏi",
    }) as HTMLInputElement;
    fireEvent.change(titleInput, { target: { value: "Tiêu đề chưa lưu" } });
    const editedSource = `${source}\nLời giải: bản nháp chưa lưu`;
    fireEvent.change(editor, { target: { value: editedSource } });
    editor.focus();
    editor.setSelectionRange(7, 7);
    expect(screen.getByRole("button", { name: "Lưu" })).toBeEnabled();

    await switchLocale("en");

    // Same DOM node: the provider state update re-renders in place without a
    // key change, so the editor never remounts.
    expect(screen.getByRole("textbox", { name: "Quiz Markdown source" })).toBe(
      editor,
    );
    expect(editor).toHaveValue(editedSource);
    expect(editor.selectionStart).toBe(7);
    expect(editor.selectionEnd).toBe(7);
    expect(titleInput).toHaveValue("Tiêu đề chưa lưu");
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
    expect(
      screen.getByRole("button", { name: "Published versions" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("region", { name: "Live quiz preview" }),
    ).toBeInTheDocument();
  });

  it.each([
    {
      locale: "vi" as const,
      expectedError:
        "Quiz Markdown có lỗi. Chọn một mục để nhảy tới vị trí nguồn.",
      validationHeading: "Xác thực xuất bản",
      publishButton: "Xuất bản QuizVersion",
      dialogTitle: "Xuất bản QuizVersion bất biến",
      sourceLabel: "Mã nguồn Quiz Markdown",
      headerPublish: "Xuất bản",
    },
    {
      locale: "en" as const,
      expectedError:
        "Quiz Markdown has errors. Select an item to jump to its source.",
      validationHeading: "Publish validation",
      publishButton: "Publish QuizVersion",
      dialogTitle: "Publish immutable QuizVersion",
      sourceLabel: "Quiz Markdown source",
      headerPublish: "Publish",
    },
  ])(
    "localizes publish validation errors in $locale",
    async ({
      dialogTitle,
      expectedError,
      headerPublish,
      locale,
      publishButton,
      sourceLabel,
      validationHeading,
    }) => {
      const source = "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 123";
      let calls = 0;
      const executor: AuthenticatedRequestExecutor = {
        execute: vi.fn(async () => {
          calls += 1;
          if (calls === 1) {
            return {
              kind: "response" as const,
              response: response(200, draft(source)),
            };
          }
          return {
            kind: "response" as const,
            response: response(400, {
              code: "QUIZ_MARKDOWN_INVALID",
              message: "Quiz Markdown validation failed.",
              status: 400,
              path: `/api/quizzes/${quizId}/versions`,
              traceId: "trace-i18n",
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
        }),
        executeOnce: vi.fn(),
        executeOnceWithAccessToken: vi.fn(),
      };
      renderAuthenticated(<QuizEditorPage quizId={quizId} />, {
        executor,
        locale,
      });
      await screen.findByRole("textbox", { name: sourceLabel });

      fireEvent.click(screen.getByRole("button", { name: headerPublish }));
      expect(
        screen.getByRole("heading", { name: dialogTitle }),
      ).toBeInTheDocument();
      fireEvent.click(screen.getByRole("button", { name: publishButton }));

      expect(
        await screen.findByRole("heading", { name: validationHeading }),
      ).toBeInTheDocument();
      expect(screen.getByText(expectedError)).toBeInTheDocument();
      // Server diagnostics stay verbatim in both locales.
      expect(screen.getByText("NUMERIC_ANSWER_INVALID")).toBeInTheDocument();
      expect(screen.getByText("Numeric answer is invalid")).toBeInTheDocument();
    },
  );

  it("keeps authored Quiz Markdown byte-for-byte across locale switches", async () => {
    const source =
      "Câu 1 [NUMERIC_FILL]: value?\r\nĐáp án: 2.50\r\nLời giải:  keep\tspacing";
    let savedBody: { authoringSource: string } | null = null;
    const executor: AuthenticatedRequestExecutor = {
      execute: vi.fn(async (request) => {
        const built = request.createRequest();
        if (built.method === "GET") {
          return {
            kind: "response" as const,
            response: response(200, draft(source)),
          };
        }
        savedBody = JSON.parse(String(built.body));
        return {
          kind: "response" as const,
          response: response(200, draft(source)),
        };
      }),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    };
    renderAuthenticated(<QuizEditorPage quizId={quizId} />, { executor });
    await screen.findByRole("textbox", {
      name: "Mã nguồn Quiz Markdown",
    });

    // Canonical grammar markers, question types and localized source jumps
    // coexist: markers stay verbatim while aria presentation is Vietnamese.
    expect(
      screen.getByRole("button", {
        name: "Tới nguồn của câu hỏi 1, dòng 1",
      }),
    ).toBeInTheDocument();
    const viPreview = screen.getByRole("region", {
      name: "Xem trước bộ câu hỏi trực tiếp",
    });
    expect(within(viPreview).getByText("NUMERIC_FILL")).toBeInTheDocument();
    expect(within(viPreview).getByText("Đáp án:")).toBeInTheDocument();
    expect(within(viPreview).getByText("Lời giải")).toBeInTheDocument();

    await switchLocale("en");
    expect(
      screen.getByRole("button", {
        name: "Jump to source for question 1, line 1",
      }),
    ).toBeInTheDocument();
    const enPreview = screen.getByRole("region", {
      name: "Live quiz preview",
    });
    expect(within(enPreview).getByText("NUMERIC_FILL")).toBeInTheDocument();
    expect(within(enPreview).getByText("Đáp án:")).toBeInTheDocument();
    expect(within(enPreview).getByText("Lời giải")).toBeInTheDocument();

    await switchLocale("vi");
    const titleInput = screen.getByRole("textbox", {
      name: "Tiêu đề bộ câu hỏi",
    });
    fireEvent.change(titleInput, { target: { value: "Network quiz (vi)" } });
    fireEvent.click(screen.getByRole("button", { name: "Lưu" }));

    await waitFor(() => expect(savedBody).not.toBeNull());
    const persisted = savedBody as { authoringSource: string } | null;
    expect(persisted?.authoringSource).toBe(source);
    expect(persisted?.authoringSource.includes("\r\n")).toBe(true);
    expect(persisted?.authoringSource.includes("\t")).toBe(true);
  });
});
