import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type {
  AttemptView,
  Result,
  Review,
} from "../api/attempt-contract.generated";
import type { AttemptApiClient } from "../api/attempt-api-client";
import {
  AttemptScreen,
  ResultScreen,
  ReviewScreen,
} from "./student-assessment-pages";
import { NO_ATTEMPT_MONITORING } from "../model/monitoring-boundary";
import { AuthProvider } from "../../auth/auth-provider";
import { AttemptEphemeralProvider } from "../hooks/attempt-ephemeral-context";
import type { AuthSessionService } from "../../auth/session/auth-session-service";
import { createAccessTokenVault } from "../../auth/session/access-token-vault";
import { createSessionRuntime } from "../../auth/session/session-runtime";
import { I18nProvider } from "../../../lib/i18n/provider";
import { LanguageSwitcher } from "../../../components/ui/language-switcher";

const navigation = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => navigation }));

const authStub: AuthSessionService = {
  authenticatedRequests: {
    async execute() {
      throw new Error("unused");
    },
    async executeOnce() {
      throw new Error("unused");
    },
    async executeOnceWithAccessToken() {
      throw new Error("unused");
    },
  },
  readAccessToken: () => null,
  bootstrap: vi.fn(async () => ({ status: "no-session" as const })),
  confirmVerification: vi.fn(),
  enableTeacher: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  register: vi.fn(),
  requestVerification: vi.fn(),
  retryVerification: vi.fn(),
  runtime: createSessionRuntime(createAccessTokenVault()),
};

function withProviders(
  element: React.ReactElement,
  initialLocale: "vi" | "en" = "vi",
) {
  return (
    <I18nProvider initialLocale={initialLocale}>
      <AuthProvider service={authStub}>
        <AttemptEphemeralProvider>
          {element}
          <LanguageSwitcher />
        </AttemptEphemeralProvider>
      </AuthProvider>
    </I18nProvider>
  );
}

const publicationId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const attemptId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";

const multiFormatAttempt: AttemptView = {
  attemptId,
  publicationId,
  ordinal: "1",
  state: "ACTIVE",
  startedAt: "2026-10-16T08:00:00Z",
  deadlineAt: "2099-10-16T08:40:00Z",
  durationMinutes: 40,
  revision: "0",
  serverNow: "2026-10-16T08:01:00Z",
  questions: [
    {
      number: 1,
      type: "SINGLE_CHOICE",
      stemMarkdown: "Choose alpha.",
      options: [
        { label: "A", markdown: "Alpha" },
        { label: "B", markdown: "Beta" },
      ],
    },
    {
      number: 2,
      type: "NUMERIC_FILL",
      stemMarkdown: "Value?",
      options: [],
    },
  ],
  answers: [
    { questionNumber: 1, answer: { type: "SINGLE_CHOICE", optionLabel: null } },
    { questionNumber: 2, answer: { type: "NUMERIC_FILL", value: null } },
  ],
  closure: null,
};

const result: Result = {
  resultId: "9bd4c564-3c27-4e6d-91aa-a004334aa8f7",
  attemptId,
  publicationId,
  ordinal: "1",
  earnedWeight: "0.25",
  possibleWeight: "1",
  percentageDisplay: "25.00",
  gradingPolicyVersion: 1,
  gradingPolicyId: "QUIZOPIA_GRADING_V1",
  terminalReason: "MANUAL_SUBMIT",
  finalizedAt: "2026-10-16T08:30:00Z",
};

function client(): AttemptApiClient {
  return {
    enterPublic: vi.fn(),
    enterClass: vi.fn(),
    start: vi.fn(),
    retake: vi.fn(),
    getAttempt: vi.fn(),
    save: vi.fn(),
    submit: vi.fn(),
    abandon: vi.fn(),
    getResult: vi.fn(),
    getAggregate: vi.fn(),
    getReview: vi.fn(),
  };
}

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.setAttribute("open", "");
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, "close", {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.removeAttribute("open");
    },
  });
});

describe("Student attempt bilingual presentation", () => {
  it("renders the attempt header and result in Vietnamese by default with canonical enum badges", async () => {
    const api = client();
    const attemptView = render(
      withProviders(
        <AttemptScreen
          attempt={multiFormatAttempt}
          client={api}
          monitoring={NO_ATTEMPT_MONITORING}
        />,
      ),
    );
    expect(
      screen.getByRole("heading", { name: "Bài kiểm tra của học sinh" }),
    ).toBeVisible();
    expect(screen.getByText("Đồng hồ đếm ngược tham khảo")).toBeVisible();
    expect(screen.getByText("Đã trả lời 0 trên 2")).toBeVisible();
    // Attempt state enum tokens remain canonical.
    expect(screen.getByText("ACTIVE")).toBeVisible();
    expect(screen.getByRole("timer").textContent).toMatch(
      /^\d{2,}:\d{2}:\d{2}$/,
    );
    attemptView.unmount();
    vi.mocked(api.getResult).mockResolvedValue({ ok: true, value: result });
    vi.mocked(api.getAggregate).mockResolvedValue({
      ok: true,
      value: { publicationId, bestResult: null },
    });
    render(withProviders(<ResultScreen attemptId={attemptId} client={api} />));
    expect(await screen.findByText("Kết quả bài làm")).toBeVisible();
    // Exact decimal display is localized without re-rounding.
    expect(screen.getByText("25,00%")).toBeVisible();
    expect(screen.getByText("0,25 trên 1 trọng số chính xác")).toBeVisible();
    expect(screen.getByText("Chưa có điểm tổng hợp.")).toBeVisible();
  });

  it("switching to English mid-attempt preserves answers, input values and the countdown node", async () => {
    const api = client();
    vi.mocked(api.save).mockResolvedValue({
      ok: true,
      value: {
        attemptId,
        questionNumber: 2,
        revision: "1",
        savedAt: "2026-10-16T08:05:00Z",
      },
    });
    render(
      withProviders(
        <AttemptScreen
          attempt={multiFormatAttempt}
          client={api}
          monitoring={NO_ATTEMPT_MONITORING}
        />,
      ),
    );

    const radio = screen.getByRole("radio", { name: /A.*Alpha/ });
    fireEvent.click(radio);
    expect(radio).toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: /^Câu hỏi 2/ }));
    const numeric = screen.getByLabelText("Câu trả lời số gồm bốn ký tự");
    fireEvent.change(numeric, { target: { value: "2.50" } });
    const timer = screen.getByRole("timer");
    const timerTextBefore = timer.textContent;

    fireEvent.change(screen.getByRole("combobox"), {
      target: { value: "en" },
    });

    // Same DOM nodes: no locale-keyed remount of the attempt screen.
    expect(
      screen.getByRole("heading", { name: "Student assessment" }),
    ).toBeVisible();
    expect(screen.getByLabelText("Four-character numeric answer")).toHaveValue(
      "2.50",
    );
    expect(screen.getByRole("timer")).toBe(timer);
    expect(timer.textContent).toEqual(timerTextBefore);

    // The earlier SINGLE_CHOICE selection also survived the locale switch.
    fireEvent.click(screen.getByRole("button", { name: /^Question 1/ }));
    expect(screen.getByRole("radio", { name: /A.*Alpha/ })).toBeChecked();
    await waitFor(() => expect(api.save).toHaveBeenCalled(), {
      timeout: 4000,
    });
  });

  it("surfaces save-status and validation errors in both locales", async () => {
    const api = client();
    vi.mocked(api.save).mockResolvedValue({
      ok: false,
      error: { kind: "api", status: 403, code: "ACCESS_DENIED" },
    });
    render(
      withProviders(
        <AttemptScreen
          attempt={multiFormatAttempt}
          client={api}
          monitoring={NO_ATTEMPT_MONITORING}
        />,
      ),
    );

    // Vietnamese validation error for an invalid numeric token.
    fireEvent.click(screen.getByRole("button", { name: /^Câu hỏi 2/ }));
    const numericVi = screen.getByLabelText("Câu trả lời số gồm bốn ký tự");
    fireEvent.change(numericVi, { target: { value: "12345" } });
    expect(
      await screen.findByText(
        "Nhập đúng bốn ký tự số, ví dụ 2.50, 1234, -2.5 hoặc -.25; để trống nghĩa là chưa trả lời.",
        {},
        { timeout: 4000 },
      ),
    ).toBeVisible();
    expect(api.save).not.toHaveBeenCalled();

    // Switch to English; the same invalid draft reports the English copy.
    fireEvent.change(screen.getByRole("combobox"), {
      target: { value: "en" },
    });
    const numericEn = screen.getByLabelText("Four-character numeric answer");
    fireEvent.change(numericEn, { target: { value: "123456" } });
    expect(
      await screen.findByText(
        "Enter exactly four numeric characters, such as 2.50, 1234, -2.5, or -.25; blank means unanswered.",
        {},
        { timeout: 4000 },
      ),
    ).toBeVisible();

    // A valid token that the server rejects renders the localized API failure.
    fireEvent.change(numericEn, { target: { value: "1.00" } });
    expect(
      await screen.findByText(
        "Student access is required for this assessment.",
        {},
        { timeout: 4000 },
      ),
    ).toBeVisible();
    expect(screen.getByText("Save failed")).toBeVisible();

    // Vietnamese renders the same failure path from the catalog.
    fireEvent.change(screen.getByRole("combobox"), {
      target: { value: "vi" },
    });
    const numericViAgain = screen.getByLabelText(
      "Câu trả lời số gồm bốn ký tự",
    );
    fireEvent.change(numericViAgain, { target: { value: "0.50" } });
    expect(
      await screen.findByText(
        "Bài kiểm tra này yêu cầu quyền Học sinh.",
        {},
        { timeout: 4000 },
      ),
    ).toBeVisible();
    expect(screen.getByText("Lưu thất bại")).toBeVisible();
  });

  it("localizes the authorized review accessibility labels", async () => {
    const api = client();
    const review: Review = {
      result,
      questions: [
        {
          question: multiFormatAttempt.questions[1]!,
          answer: { type: "NUMERIC_FILL", value: "0001" },
          correctAnswer: { type: "NUMERIC_FILL", value: "1.00" },
          earnedWeight: "1",
          possibleWeight: "1",
          explanationMarkdown: "Numerically equal exact decimals.",
        },
      ],
    };
    vi.mocked(api.getReview).mockResolvedValue({ ok: true, value: review });
    render(withProviders(<ReviewScreen attemptId={attemptId} client={api} />));
    expect(
      await screen.findByRole("heading", { name: "Xem lại câu trả lời" }),
    ).toBeVisible();
    expect(screen.getByText("Câu trả lời của bạn")).toBeVisible();
    expect(screen.getByText("Đáp án đúng")).toBeVisible();
    expect(screen.getByText("Lời giải")).toBeVisible();
    // Teacher-authored stems stay verbatim; exact numeric answers stay unrounded.
    expect(screen.getByText("Value?")).toBeVisible();
    expect(screen.getByText("1.00")).toBeVisible();
  });
});
