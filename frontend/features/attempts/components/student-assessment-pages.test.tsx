import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type {
  AttemptView,
  Result,
  Review,
  StudentView,
} from "../api/attempt-contract.generated";
import type { AttemptApiClient } from "../api/attempt-api-client";
import {
  AttemptScreen,
  IntegratedAttemptScreen,
  ResultScreen,
  ReviewScreen,
  StudentAssessmentJoinScreen,
} from "./student-assessment-pages";
import { NO_ATTEMPT_MONITORING } from "../model/monitoring-boundary";
import { AuthProvider } from "../../auth/auth-provider";
import type { AuthSessionService } from "../../auth/session/auth-session-service";
import { createAccessTokenVault } from "../../auth/session/access-token-vault";
import { createSessionRuntime } from "../../auth/session/session-runtime";

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

function withAuth(element: React.ReactElement) {
  return <AuthProvider service={authStub}>{element}</AuthProvider>;
}

const navigation = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
const ephemeral = vi.hoisted(() => ({
  getPublicLocator: vi.fn(() => null),
  rememberPublicLocator: vi.fn(),
}));
const monitoringMock = vi.hoisted(() => ({
  controller: {
    state: { status: "idle" } as {
      status: string;
      session?: unknown;
      activity?: unknown;
      error?: unknown;
      reason?: string;
    },
    start: vi.fn(),
    verifyAndStart: vi.fn(),
    stop: vi.fn(),
    questionViewed: vi.fn(),
    questionLeft: vi.fn(),
    answerSelected: vi.fn(),
    answerChanged: vi.fn(),
    answerCleared: vi.fn(),
  },
}));
vi.mock("next/navigation", () => ({ useRouter: () => navigation }));
vi.mock("../hooks/attempt-ephemeral-context", () => ({
  useAttemptEphemeralContext: () => ephemeral,
}));
vi.mock("../../proctoring/hooks/use-student-monitoring", () => ({
  useStudentMonitoring: () => monitoringMock.controller,
}));
vi.mock("../../proctoring/components/student-camera", () => ({
  StudentCamera: () => (
    <div data-testid="student-camera">Mock Student Camera</div>
  ),
}));

const publicationId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const attemptId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const locator = "P".repeat(43);
const preview: StudentView = {
  publicationId,
  title: "Student contract assessment",
  description: "No answer keys in this preview",
  audience: "PUBLIC",
  startAt: "2026-10-16T08:00:00Z",
  endAt: "2026-10-16T09:00:00Z",
  durationMinutes: 40,
  monitoringRequired: false,
  windowStatus: "AVAILABLE",
};
const attempt: AttemptView = {
  attemptId,
  publicationId,
  ordinal: "1",
  state: "ACTIVE",
  startedAt: preview.startAt,
  deadlineAt: "2026-10-16T08:40:00Z",
  durationMinutes: 40,
  revision: "0",
  serverNow: "2026-10-16T08:01:00Z",
  questions: [
    { number: 1, type: "NUMERIC_FILL", stemMarkdown: "Value?", options: [] },
  ],
  answers: [
    { questionNumber: 1, answer: { type: "NUMERIC_FILL", value: null } },
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
    getAggregate: vi.fn().mockResolvedValue({
      ok: true,
      value: { publicationId, bestResult: result },
    }),
    getReview: vi.fn(),
  };
}

beforeEach(() => {
  navigation.push.mockClear();
  navigation.replace.mockClear();
  ephemeral.rememberPublicLocator.mockClear();
  monitoringMock.controller.start.mockClear();
  monitoringMock.controller.stop.mockClear();
  monitoringMock.controller.questionViewed.mockClear();
  monitoringMock.controller.questionLeft.mockClear();
  monitoringMock.controller.answerSelected.mockClear();
  monitoringMock.controller.answerChanged.mockClear();
  monitoringMock.controller.answerCleared.mockClear();
  monitoringMock.controller.state = { status: "idle" };
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

describe("Student assessment entry and result boundaries", () => {
  it("manually enters a confidential PUBLIC locator and remembers it only after authorized start", async () => {
    const api = client();
    vi.mocked(api.enterPublic).mockResolvedValue({ ok: true, value: preview });
    vi.mocked(api.start).mockResolvedValue({ ok: true, value: attempt });
    render(<StudentAssessmentJoinScreen client={api} />);
    fireEvent.change(screen.getByLabelText("43-character private locator"), {
      target: { value: locator },
    });
    fireEvent.click(screen.getByRole("button", { name: "Check access" }));
    expect(
      await screen.findByText("Student contract assessment"),
    ).toBeVisible();
    expect(api.enterPublic).toHaveBeenCalledWith({ locator });
    fireEvent.click(screen.getByRole("button", { name: "Start or resume" }));
    await waitFor(() => expect(api.start).toHaveBeenCalledTimes(1));
    expect(vi.mocked(api.start).mock.calls[0]?.slice(0, 2)).toEqual([
      publicationId,
      { locator },
    ]);
    expect(vi.mocked(api.start).mock.calls[0]?.[2]).toMatch(
      /^[A-Za-z0-9_-]{16,128}$/,
    );
    expect(ephemeral.rememberPublicLocator).toHaveBeenCalledWith(
      publicationId,
      locator,
    );
    expect(navigation.push).toHaveBeenCalledWith(`/app/attempts/${attemptId}`);
  });

  it("supports bodyless CLASS entry by a known canonical Publication ID", async () => {
    const api = client();
    vi.mocked(api.enterClass).mockResolvedValue({
      ok: true,
      value: { ...preview, audience: "CLASS" },
    });
    render(<StudentAssessmentJoinScreen client={api} />);
    fireEvent.click(
      screen.getByRole("tab", { name: "Known class Publication" }),
    );
    fireEvent.change(screen.getByLabelText("Publication ID"), {
      target: { value: publicationId },
    });
    fireEvent.click(screen.getByRole("button", { name: "Check access" }));
    await waitFor(() =>
      expect(api.enterClass).toHaveBeenCalledWith(publicationId),
    );
    expect(api.enterPublic).not.toHaveBeenCalled();
  });

  it("drops confidential entry state when the identity-scoped screen remounts", async () => {
    const api = client();
    vi.mocked(api.enterPublic).mockResolvedValue({ ok: true, value: preview });
    const { rerender } = render(
      <StudentAssessmentJoinScreen client={api} key="student-a:1" />,
    );
    fireEvent.change(screen.getByLabelText("43-character private locator"), {
      target: { value: locator },
    });
    fireEvent.click(screen.getByRole("button", { name: "Check access" }));
    expect(
      await screen.findByText("Student contract assessment"),
    ).toBeVisible();

    rerender(<StudentAssessmentJoinScreen client={api} key="student-b:2" />);
    expect(screen.getByLabelText("43-character private locator")).toHaveValue(
      "",
    );
    expect(
      screen.queryByText("Student contract assessment"),
    ).not.toBeInTheDocument();
  });

  it("retains an uncertain start and prevents switching to another entry intent", async () => {
    const api = client();
    vi.mocked(api.enterPublic).mockResolvedValue({ ok: true, value: preview });
    vi.mocked(api.start).mockResolvedValue({
      ok: false,
      error: { kind: "unavailable", reason: "timeout" },
    });
    render(<StudentAssessmentJoinScreen client={api} />);
    fireEvent.change(screen.getByLabelText("43-character private locator"), {
      target: { value: locator },
    });
    fireEvent.click(screen.getByRole("button", { name: "Check access" }));
    await screen.findByText("Student contract assessment");
    fireEvent.click(screen.getByRole("button", { name: "Start or resume" }));

    expect(await screen.findByText("Start outcome is uncertain")).toBeVisible();
    expect(
      screen.getByRole("tab", { name: "Known class Publication" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Retry same start request" }),
    ).toBeEnabled();
  });

  it("guards the exact recovery key while a start request is still in flight", async () => {
    const api = client();
    let resolveStart!: (
      value: Awaited<ReturnType<AttemptApiClient["start"]>>,
    ) => void;
    const pendingStart = new Promise<
      Awaited<ReturnType<AttemptApiClient["start"]>>
    >((resolve) => {
      resolveStart = resolve;
    });
    vi.mocked(api.enterPublic).mockResolvedValue({ ok: true, value: preview });
    vi.mocked(api.start).mockReturnValue(pendingStart);
    render(<StudentAssessmentJoinScreen client={api} />);
    fireEvent.change(screen.getByLabelText("43-character private locator"), {
      target: { value: locator },
    });
    fireEvent.click(screen.getByRole("button", { name: "Check access" }));
    await screen.findByText("Student contract assessment");
    fireEvent.click(screen.getByRole("button", { name: "Start or resume" }));
    await waitFor(() => expect(api.start).toHaveBeenCalledTimes(1));

    await waitFor(() => {
      const leave = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(leave);
      expect(leave.defaultPrevented).toBe(true);
    });

    await act(async () => resolveStart({ ok: true, value: attempt }));
  });

  it("clears a retained start intent after an exact retry fails definitively", async () => {
    const api = client();
    vi.mocked(api.enterPublic).mockResolvedValue({ ok: true, value: preview });
    vi.mocked(api.start)
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "unavailable", reason: "timeout" },
      })
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 409, code: "ACTIVE_ATTEMPT_EXISTS" },
      });
    render(<StudentAssessmentJoinScreen client={api} />);
    fireEvent.change(screen.getByLabelText("43-character private locator"), {
      target: { value: locator },
    });
    fireEvent.click(screen.getByRole("button", { name: "Check access" }));
    await screen.findByText("Student contract assessment");
    fireEvent.click(screen.getByRole("button", { name: "Start or resume" }));
    fireEvent.click(
      await screen.findByRole("button", { name: "Retry same start request" }),
    );

    expect(
      await screen.findByText(
        "An active attempt already exists. Resume it instead.",
      ),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Retry same start request" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("tab", { name: "Known class Publication" }),
    ).toBeEnabled();
  });

  it("renders only server-issued exact totals and does not request review automatically", async () => {
    const api = client();
    vi.mocked(api.getResult).mockResolvedValue({ ok: true, value: result });
    render(<ResultScreen attemptId={attemptId} client={api} />);
    expect(await screen.findByText("25.00%")).toBeVisible();
    expect(screen.getByText("0.25 of 1 exact weight")).toBeVisible();
    expect(api.getReview).not.toHaveBeenCalled();
    expect(
      screen.getByRole("link", { name: "Request answer review" }),
    ).toHaveAttribute("href", `/app/attempts/${attemptId}/review`);
  });

  it("refreshes a terminal revision conflict before a deliberate retry", async () => {
    const api = client();
    vi.mocked(api.submit)
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 409, code: "REVISION_CONFLICT" },
      })
      .mockResolvedValueOnce({ ok: true, value: result });
    vi.mocked(api.getAttempt).mockResolvedValue({
      ok: true,
      value: { ...attempt, revision: "1" },
    });
    render(
      withAuth(
        <AttemptScreen
          attempt={attempt}
          client={api}
          monitoring={NO_ATTEMPT_MONITORING}
        />,
      ),
    );

    fireEvent.click(screen.getByRole("button", { name: "Submit attempt" }));
    fireEvent.click(
      screen.getByRole("button", { name: "Submit complete answers" }),
    );
    expect(
      await screen.findByText(/Attempt changed on the server/),
    ).toBeVisible();
    expect(api.getAttempt).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "Submit attempt" }));
    fireEvent.click(
      screen.getByRole("button", { name: "Submit complete answers" }),
    );
    await waitFor(() => expect(api.submit).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.submit).mock.calls[1]?.[1]).toMatchObject({
      expectedRevision: "1",
    });
    expect(vi.mocked(api.submit).mock.calls[1]?.[2]).not.toBe(
      vi.mocked(api.submit).mock.calls[0]?.[2],
    );
  });

  it("guards the exact recovery key while a terminal mutation is in flight", async () => {
    const api = client();
    let resolveSubmit!: (
      value: Awaited<ReturnType<AttemptApiClient["submit"]>>,
    ) => void;
    vi.mocked(api.submit).mockReturnValue(
      new Promise<Awaited<ReturnType<AttemptApiClient["submit"]>>>(
        (resolve) => {
          resolveSubmit = resolve;
        },
      ),
    );
    render(
      withAuth(
        <AttemptScreen
          attempt={attempt}
          client={api}
          monitoring={NO_ATTEMPT_MONITORING}
        />,
      ),
    );
    fireEvent.click(screen.getByRole("button", { name: "Submit attempt" }));
    fireEvent.click(
      screen.getByRole("button", { name: "Submit complete answers" }),
    );
    await waitFor(() => expect(api.submit).toHaveBeenCalledTimes(1));

    await waitFor(() => {
      const leave = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(leave);
      expect(leave.defaultPrevented).toBe(true);
    });

    await act(async () => resolveSubmit({ ok: true, value: result }));
  });

  it("handles withheld review without exposing correct answers", async () => {
    const api = client();
    vi.mocked(api.getReview).mockResolvedValue({
      ok: false,
      error: { kind: "api", status: 409, code: "REVIEW_NOT_AVAILABLE" },
    });
    render(<ReviewScreen attemptId={attemptId} client={api} />);
    expect(await screen.findByText("Answer review unavailable")).toBeVisible();
    expect(screen.queryByText("Correct answer")).not.toBeInTheDocument();
  });

  it("shows server-authorized correct answers only from a successful Review response", async () => {
    const api = client();
    const review: Review = {
      result,
      questions: [
        {
          question: attempt.questions[0]!,
          answer: { type: "NUMERIC_FILL", value: "0001" },
          correctAnswer: { type: "NUMERIC_FILL", value: "1.00" },
          earnedWeight: "1",
          possibleWeight: "1",
          explanationMarkdown: "Numerically equal exact decimals.",
        },
      ],
    };
    vi.mocked(api.getReview).mockResolvedValue({ ok: true, value: review });
    render(<ReviewScreen attemptId={attemptId} client={api} />);
    expect(await screen.findByText("Correct answer")).toBeVisible();
    expect(screen.getByText("1.00")).toBeVisible();
    expect(screen.getByText("Numerically equal exact decimals.")).toBeVisible();
  });
});

describe("IntegratedAttemptScreen and Proctoring monitoring composition", () => {
  const multiQuestionAttempt: AttemptView = {
    ...attempt,
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
        stemMarkdown: "Enter token.",
        options: [],
      },
    ],
    answers: [
      {
        questionNumber: 1,
        answer: { type: "SINGLE_CHOICE", optionLabel: null },
      },
      { questionNumber: 2, answer: { type: "NUMERIC_FILL", value: null } },
    ],
  };

  it("renders AttemptScreen without proctoring overhead when projection is not-required", () => {
    const api = client();
    render(
      withAuth(
        <IntegratedAttemptScreen
          attempt={attempt}
          client={api}
          projection={{ status: "not-required" }}
        />,
      ),
    );
    expect(screen.queryByText(/monitoring/i)).not.toBeInTheDocument();
    expect(monitoringMock.controller.start).not.toHaveBeenCalled();
  });

  it("displays checking alert while projection is verifying without blocking interaction", () => {
    const api = client();
    render(
      withAuth(
        <IntegratedAttemptScreen
          attempt={attempt}
          client={api}
          projection={{ status: "checking" }}
        />,
      ),
    );
    expect(
      screen.getByText("Checking monitoring requirement"),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        /Assessment work is available while the authorized CLASS projection is checked/,
      ),
    ).toBeInTheDocument();
    expect(monitoringMock.controller.start).not.toHaveBeenCalled();
  });

  it("displays monitoring authority unavailable warning when check fails", () => {
    const api = client();
    render(
      withAuth(
        <IntegratedAttemptScreen
          attempt={attempt}
          client={api}
          projection={{ status: "unavailable" }}
        />,
      ),
    );
    expect(
      screen.getByText("Monitoring authority unavailable"),
    ).toBeInTheDocument();
    expect(monitoringMock.controller.start).not.toHaveBeenCalled();
  });

  it("starts monitoring and renders camera banner when projection is eligible", async () => {
    const api = client();
    monitoringMock.controller.state = { status: "idle" };
    render(
      withAuth(
        <IntegratedAttemptScreen
          attempt={attempt}
          client={api}
          projection={{
            status: "eligible",
            eligibility: {
              attemptId,
              audience: "CLASS",
              monitoringRequired: true,
              attemptStatus: "ACTIVE",
              deadlineAt: "2026-10-16T12:00:00Z",
            },
          }}
        />,
      ),
    );
    await waitFor(() =>
      expect(monitoringMock.controller.start).toHaveBeenCalledWith({
        attemptId,
        audience: "CLASS",
        monitoringRequired: true,
        attemptStatus: "ACTIVE",
        deadlineAt: "2026-10-16T12:00:00Z",
      }),
    );
  });

  it("renders active monitoring banner and StudentCamera when monitoring state is active", () => {
    const api = client();
    monitoringMock.controller.state = {
      status: "active",
      session: {
        id: "session-1",
        attemptId,
        deadlineAt: "2026-10-16T12:00:00Z",
        status: "ACTIVE",
      },
      activity: { getSnapshot: () => ({ delivery: "live" }) },
    };
    render(
      withAuth(
        <IntegratedAttemptScreen
          attempt={attempt}
          client={api}
          projection={{
            status: "eligible",
            eligibility: {
              attemptId,
              audience: "CLASS",
              monitoringRequired: true,
              attemptStatus: "ACTIVE",
              deadlineAt: "2026-10-16T12:00:00Z",
            },
          }}
        />,
      ),
    );
    expect(screen.getByText("Activity monitoring active")).toBeInTheDocument();
    expect(screen.getByTestId("student-camera")).toBeInTheDocument();
  });

  it("forwards answer interactions and question selection events to the monitoring controller", async () => {
    const api = client();
    monitoringMock.controller.state = {
      status: "active",
      session: {
        id: "session-1",
        attemptId,
        deadlineAt: "2026-10-16T12:00:00Z",
        status: "ACTIVE",
      },
      activity: { getSnapshot: () => ({ delivery: "live" }) },
    };
    render(
      withAuth(
        <IntegratedAttemptScreen
          attempt={multiQuestionAttempt}
          client={api}
          projection={{
            status: "eligible",
            eligibility: {
              attemptId,
              audience: "CLASS",
              monitoringRequired: true,
              attemptStatus: "ACTIVE",
              deadlineAt: "2026-10-16T12:00:00Z",
            },
          }}
        />,
      ),
    );

    await waitFor(() =>
      expect(monitoringMock.controller.questionViewed).toHaveBeenCalledWith(1),
    );

    const radio = screen.getByRole("radio", { name: /A.*Alpha/ });
    fireEvent.click(radio);
    expect(monitoringMock.controller.answerSelected).toHaveBeenCalledWith(1);

    fireEvent.click(screen.getByRole("button", { name: /Question 2/ }));
    expect(monitoringMock.controller.questionLeft).toHaveBeenCalledWith(1);
    expect(monitoringMock.controller.questionViewed).toHaveBeenCalledWith(2);
  });

  it("recovers from temporary Proctoring 503 failure when user clicks retry on an active eligible attempt", async () => {
    const api = client();
    monitoringMock.controller.state = {
      status: "unavailable",
      error: { kind: "api", status: 503, message: "Service Unavailable" },
    };
    render(
      withAuth(
        <IntegratedAttemptScreen
          attempt={attempt}
          client={api}
          projection={{
            status: "eligible",
            eligibility: {
              attemptId,
              audience: "CLASS",
              monitoringRequired: true,
              attemptStatus: "ACTIVE",
              deadlineAt: "2026-10-16T12:00:00Z",
            },
          }}
        />,
      ),
    );

    expect(screen.getByText("Monitoring unavailable")).toBeInTheDocument();
    const retryButton = screen.getByRole("button", {
      name: /retry monitoring verification/i,
    });
    expect(retryButton).toBeInTheDocument();

    monitoringMock.controller.start.mockClear();
    fireEvent.click(retryButton);

    expect(monitoringMock.controller.start).toHaveBeenCalledWith({
      attemptId,
      audience: "CLASS",
      monitoringRequired: true,
      attemptStatus: "ACTIVE",
      deadlineAt: "2026-10-16T12:00:00Z",
    });
  });

  it("denies monitoring retry when attempt is submitted or terminal", () => {
    const api = client();
    monitoringMock.controller.state = {
      status: "unavailable",
      error: { kind: "api", status: 503, message: "Service Unavailable" },
    };
    render(
      withAuth(
        <IntegratedAttemptScreen
          attempt={{ ...attempt, state: "SUBMITTED" }}
          client={api}
          projection={{
            status: "eligible",
            eligibility: {
              attemptId,
              audience: "CLASS",
              monitoringRequired: true,
              attemptStatus: "ACTIVE",
              deadlineAt: "2026-10-16T12:00:00Z",
            },
          }}
        />,
      ),
    );

    expect(
      screen.queryByRole("button", {
        name: /retry monitoring verification/i,
      }),
    ).not.toBeInTheDocument();
    expect(monitoringMock.controller.start).not.toHaveBeenCalled();
  });

  it("denies monitoring retry when attempt deadline has expired", () => {
    const api = client();
    monitoringMock.controller.state = {
      status: "unavailable",
      error: { kind: "api", status: 503, message: "Service Unavailable" },
    };
    render(
      withAuth(
        <IntegratedAttemptScreen
          attempt={{
            ...attempt,
            deadlineAt: "2026-10-16T08:00:00Z",
            serverNow: "2026-10-16T08:05:00Z",
          }}
          client={api}
          projection={{
            status: "eligible",
            eligibility: {
              attemptId,
              audience: "CLASS",
              monitoringRequired: true,
              attemptStatus: "ACTIVE",
              deadlineAt: "2026-10-16T08:00:00Z",
            },
          }}
        />,
      ),
    );

    expect(
      screen.queryByRole("button", {
        name: /retry monitoring verification/i,
      }),
    ).not.toBeInTheDocument();
    expect(monitoringMock.controller.start).not.toHaveBeenCalled();
  });

  it("denies monitoring retry when authority is lost (403 / AUTHORITY_LOST)", () => {
    const api = client();
    monitoringMock.controller.state = {
      status: "unavailable",
      error: { kind: "api", status: 403, code: "AUTHORITY_LOST" },
    };
    render(
      withAuth(
        <IntegratedAttemptScreen
          attempt={attempt}
          client={api}
          projection={{
            status: "eligible",
            eligibility: {
              attemptId,
              audience: "CLASS",
              monitoringRequired: true,
              attemptStatus: "ACTIVE",
              deadlineAt: "2026-10-16T12:00:00Z",
            },
          }}
        />,
      ),
    );

    expect(screen.getByText("Monitoring unavailable")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", {
        name: /retry monitoring verification/i,
      }),
    ).not.toBeInTheDocument();
    expect(monitoringMock.controller.start).not.toHaveBeenCalled();
  });

  it("denies monitoring retry when monitoring status is ended or ineligible", () => {
    const api = client();
    monitoringMock.controller.state = { status: "ended" };
    const { rerender } = render(
      withAuth(
        <IntegratedAttemptScreen
          attempt={attempt}
          client={api}
          projection={{
            status: "eligible",
            eligibility: {
              attemptId,
              audience: "CLASS",
              monitoringRequired: true,
              attemptStatus: "ACTIVE",
              deadlineAt: "2026-10-16T12:00:00Z",
            },
          }}
        />,
      ),
    );

    expect(screen.getByText("Monitoring unavailable")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", {
        name: /retry monitoring verification/i,
      }),
    ).not.toBeInTheDocument();

    monitoringMock.controller.state = {
      status: "ineligible",
      reason: "Audience not monitored",
    };
    rerender(
      withAuth(
        <IntegratedAttemptScreen
          attempt={attempt}
          client={api}
          projection={{
            status: "eligible",
            eligibility: {
              attemptId,
              audience: "CLASS",
              monitoringRequired: true,
              attemptStatus: "ACTIVE",
              deadlineAt: "2026-10-16T12:00:00Z",
            },
          }}
        />,
      ),
    );

    expect(screen.getByText("Monitoring unavailable")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", {
        name: /retry monitoring verification/i,
      }),
    ).not.toBeInTheDocument();
  });
});

describe("Focused attempt workspace", () => {
  const workspaceAttempt: AttemptView = {
    ...attempt,
    deadlineAt: null,
    durationMinutes: 0,
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
        stemMarkdown: "Enter token.",
        options: [],
      },
      {
        number: 3,
        type: "TRUE_FALSE_MATRIX",
        stemMarkdown: "Judge statements.",
        options: [
          { label: "A", markdown: "Statement A" },
          { label: "B", markdown: "Statement B" },
          { label: "C", markdown: "Statement C" },
          { label: "D", markdown: "Statement D" },
        ],
      },
    ],
    answers: [
      {
        questionNumber: 1,
        answer: { type: "SINGLE_CHOICE", optionLabel: null },
      },
      { questionNumber: 2, answer: { type: "NUMERIC_FILL", value: null } },
      {
        questionNumber: 3,
        answer: {
          type: "TRUE_FALSE_MATRIX",
          judgments: { A: null, B: null, C: null, D: null },
        },
      },
    ],
  };

  it("renders every question card in canonical order with type labels and stable navigator links", () => {
    const api = client();
    render(
      withAuth(
        <AttemptScreen
          attempt={workspaceAttempt}
          client={api}
          monitoring={NO_ATTEMPT_MONITORING}
        />,
      ),
    );
    const stems = screen.getAllByRole("heading", { level: 2 });
    expect(stems.map((heading) => heading.textContent)).toEqual([
      "Choose alpha.",
      "Enter token.",
      "Judge statements.",
    ]);
    expect(screen.getByText("Question 1 · SINGLE CHOICE")).toBeVisible();
    expect(screen.getByText("Question 2 · NUMERIC FILL")).toBeVisible();
    expect(screen.getByText("Question 3 · TRUE FALSE MATRIX")).toBeVisible();
    // Navigator boxes address questions by their stable canonical numbers.
    expect(
      screen.getByRole("button", { name: /^Question 2, Unanswered/ }),
    ).toBeVisible();
  });

  it("shows no countdown at all for an unlimited attempt while submit stays available", () => {
    const api = client();
    render(
      withAuth(
        <AttemptScreen
          attempt={workspaceAttempt}
          client={api}
          monitoring={NO_ATTEMPT_MONITORING}
        />,
      ),
    );
    expect(screen.queryByRole("timer")).not.toBeInTheDocument();
    expect(screen.queryByText("Informative countdown")).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Submit attempt" }),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Abandon attempt" }),
    ).toBeVisible();
  });

  it("shows the informative countdown and deadline only when a deadline exists", () => {
    const api = client();
    render(
      withAuth(
        <AttemptScreen
          attempt={{ ...attempt, deadlineAt: "2026-10-16T08:40:00Z" }}
          client={api}
          monitoring={NO_ATTEMPT_MONITORING}
        />,
      ),
    );
    expect(screen.getByRole("timer")).toBeInTheDocument();
    expect(screen.getByText("Informative countdown")).toBeVisible();
    expect(screen.getByText(/^Deadline /)).toBeVisible();
  });

  it("tracks answered versus unanswered questions in the navigator without implying persistence", () => {
    const api = client();
    render(
      withAuth(
        <AttemptScreen
          attempt={workspaceAttempt}
          client={api}
          monitoring={NO_ATTEMPT_MONITORING}
        />,
      ),
    );
    // Before any edit every box reports unanswered with the saved phase.
    expect(
      screen.getByRole("button", { name: "Question 1, Unanswered · Saved" }),
    ).toBeVisible();

    fireEvent.click(screen.getByRole("radio", { name: /A.*Alpha/ }));
    // Answered but only a local draft: the label never claims the save landed.
    expect(
      screen.getByRole("button", {
        name: "Question 1, Answered · Unsaved draft",
      }),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Question 2, Unanswered · Saved" }),
    ).toBeVisible();
    expect(screen.getByText("1 of 3 answered")).toBeVisible();
  });

  it("moves the current indicator through stable question numbers on navigator jumps", () => {
    const api = client();
    render(
      withAuth(
        <AttemptScreen
          attempt={workspaceAttempt}
          client={api}
          monitoring={NO_ATTEMPT_MONITORING}
        />,
      ),
    );
    const first = screen.getByRole("button", {
      name: "Question 1, Unanswered · Saved",
    });
    expect(first).toHaveAttribute("aria-current", "true");
    fireEvent.click(
      screen.getByRole("button", { name: "Question 3, Unanswered · Saved" }),
    );
    expect(
      screen.getByRole("button", { name: "Question 3, Unanswered · Saved" }),
    ).toHaveAttribute("aria-current", "true");
    expect(first).not.toHaveAttribute("aria-current");
  });

  it("hides terminal actions once the attempt is no longer ACTIVE", () => {
    const api = client();
    render(
      withAuth(
        <AttemptScreen
          attempt={{ ...workspaceAttempt, state: "SUBMITTED" }}
          client={api}
          monitoring={NO_ATTEMPT_MONITORING}
        />,
      ),
    );
    expect(
      screen.queryByRole("button", { name: "Submit attempt" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Abandon attempt" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("Attempt submitted")).toBeVisible();
  });
});
