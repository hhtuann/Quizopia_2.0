import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type {
  QuizApiClient,
  QuizVersionDetail,
} from "../../quizzes/api/quiz-api-client";
import type {
  PublicationApiClient,
  PublicationResult,
} from "../api/publication-api-client";
import type {
  Configuration,
  OwnerView,
} from "../api/publication-contract.generated";
import {
  PublicationCreateScreen,
  PublicationDetailScreen,
} from "./publication-pages";
import { PublicationConfigurationForm } from "./publication-configuration-form";
import { AuthProvider } from "../../auth/auth-provider";
import type { AuthSessionService } from "../../auth/session/auth-session-service";
import { createAccessTokenVault } from "../../auth/session/access-token-vault";
import { createSessionRuntime } from "../../auth/session/session-runtime";
import { confirmUnsavedWorkLeaving } from "../../attempts/hooks/use-unsaved-navigation-guard";

const authRuntime = createSessionRuntime(createAccessTokenVault());
vi.mock("../../classrooms/use-classroom-client", () => ({
  useClassroomClient: () => null,
}));
// The embedded PublicationResultsSection fetches through this hook; stub it
// so detail-screen tests control results without a live auth executor.
const resultsClient = vi.hoisted(() => ({
  results: vi.fn(),
}));
vi.mock("../../teacher-results/hooks/use-teacher-results-client", () => ({
  useTeacherResultsClient: () => resultsClient,
}));
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
  runtime: authRuntime,
};

function renderDetail(element: React.ReactElement) {
  return render(<AuthProvider service={authStub}>{element}</AuthProvider>);
}

const navigation = vi.hoisted(() => ({
  push: vi.fn(),
  replace: vi.fn(),
  pathname: "/app/assessments/new",
}));
vi.mock("next/navigation", () => ({
  useRouter: () => navigation,
  usePathname: () => navigation.pathname,
}));
const id = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const source = {
  quizId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  quizVersionId: "9bd4c564-3c27-4e6d-91aa-a004334aa8f7",
  versionNumber: 3,
};
const locator = "A".repeat(43);

/** Timezone-safe local date/time parts for filling DateTimeField inputs. */
function localParts(utc: string): { date: string; time: string } {
  const d = new Date(utc);
  const pad = (n: number) => String(n).padStart(2, "0");
  return {
    date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
    time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
  };
}
const configuration: Configuration = {
  ...source,
  title: "Immutable network assessment",
  description: "Original source",
  audience: "PUBLIC",
  classroomId: null,
  startAt: "2026-10-16T08:00:00.000Z",
  endAt: "2026-10-16T09:00:00.000Z",
  durationMinutes: 40,
  monitoringChoice: "DISABLED",
  maxAttempts: 2,
};
const draft: OwnerView = {
  publicationId: id,
  configuration,
  state: "DRAFT",
  revision: 0,
  createdAt: "2026-10-16T07:00:00Z",
  openedAt: null,
  privateLocator: locator,
};
const opened: OwnerView = {
  ...draft,
  state: "OPEN",
  revision: 1,
  openedAt: "2026-10-16T07:30:00Z",
};
const version: QuizVersionDetail = {
  id: source.quizVersionId,
  quizId: source.quizId,
  versionNumber: 3,
  titleSnapshot: configuration.title,
  descriptionSnapshot: configuration.description ?? null,
  contentSchemaVersion: 1,
  createdAt: draft.createdAt,
  sourceSnapshot: "private answer-bearing source",
  structuredContent: { questions: [] },
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

function client() {
  return {
    list: vi.fn<PublicationApiClient["list"]>(),
    create: vi.fn<PublicationApiClient["create"]>(),
    get: vi
      .fn<PublicationApiClient["get"]>()
      .mockResolvedValue({ ok: true, value: draft }),
    update: vi.fn<PublicationApiClient["update"]>(),
    open: vi.fn<PublicationApiClient["open"]>(),
    extend: vi.fn<PublicationApiClient["extend"]>(),
    close: vi.fn<PublicationApiClient["close"]>(),
  };
}

function quizzes(): QuizApiClient {
  return {
    createDraft: vi.fn(),
    getDraft: vi.fn(),
    listOwnedQuizzes: vi.fn(),
    listPublishedVersions: vi.fn(),
    getPublishedVersion: vi.fn(async () => ({
      ok: true as const,
      value: version,
    })),
    publishDraft: vi.fn(),
    updateDraft: vi.fn(),
  };
}

beforeEach(() => {
  navigation.push.mockClear();
  resultsClient.results.mockReset();
  resultsClient.results.mockResolvedValue({
    ok: true,
    status: 200,
    value: { items: [], nextCursor: null },
  });
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

function localPartsFor(utcInstant: string): { date: string; time: string } {
  const d = new Date(utcInstant);
  const pad = (n: number) => String(n).padStart(2, "0");
  return {
    date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
    time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
  };
}

async function fillCreate() {
  await screen.findByLabelText("Publication title");
  fireEvent.click(screen.getByRole("radio", { name: "Public" }));
  const start = localPartsFor("2026-10-16T08:00:00Z");
  const end = localPartsFor("2026-10-16T09:00:00Z");
  fireEvent.change(screen.getByLabelText("Participation start"), {
    target: { value: start.date },
  });
  fireEvent.change(screen.getByLabelText("Participation start time"), {
    target: { value: start.time },
  });
  fireEvent.change(screen.getByLabelText("Participation end"), {
    target: { value: end.date },
  });
  fireEvent.change(screen.getByLabelText("Participation end time"), {
    target: { value: end.time },
  });
}

function confirm(label: string) {
  fireEvent.click(
    within(screen.getByRole("dialog")).getByRole("button", {
      name: label,
    }),
  );
}

describe("Teacher Publication creation", () => {
  it("verifies the pinned version and reviews a PUBLIC DRAFT before sending", async () => {
    const api = client();
    const quiz = quizzes();
    api.create.mockResolvedValue({ ok: true, value: draft });
    render(
      <PublicationCreateScreen
        client={api}
        quizClient={quiz}
        source={source}
      />,
    );
    await fillCreate();
    expect(quiz.getPublishedVersion).toHaveBeenCalledWith(
      source.quizId,
      3,
      expect.any(AbortSignal),
    );
    expect(screen.queryByText(version.sourceSnapshot)).not.toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "Review and create DRAFT" }),
    );
    expect(api.create).not.toHaveBeenCalled();
    expect(
      within(screen.getByRole("dialog")).getByText(source.quizVersionId),
    ).toBeInTheDocument();
    confirm("Create DRAFT");
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(1));
    expect(api.create.mock.calls[0]?.[0]).toMatchObject({
      ...source,
      audience: "PUBLIC",
      classroomId: null,
      monitoringChoice: "DISABLED",
      startAt: "2026-10-16T08:00:00.000Z",
    });
    expect(api.create.mock.calls[0]?.[1]).toMatch(/^[A-Za-z0-9_-]{16,128}$/);
    expect(navigation.push).toHaveBeenCalledWith(`/app/assessments/${id}`);
  });

  it("refuses a mismatched immutable source instead of selecting the latest version", async () => {
    const quiz = quizzes();
    vi.mocked(quiz.getPublishedVersion).mockResolvedValue({
      ok: true,
      value: { ...version, id },
    });
    const api = client();
    render(
      <PublicationCreateScreen
        client={api}
        quizClient={quiz}
        source={source}
      />,
    );
    await screen.findByText("Selected QuizVersion could not be verified");
    expect(
      screen.queryByLabelText("Publication title"),
    ).not.toBeInTheDocument();
    expect(api.create).not.toHaveBeenCalled();
  });

  it("locks uncertain creation and retries the identical body and key without concurrent submissions", async () => {
    const api = client();
    const pending = deferred<PublicationResult<OwnerView>>();
    api.create
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce({ ok: true, value: draft });
    render(
      <PublicationCreateScreen
        client={api}
        quizClient={quizzes()}
        source={source}
      />,
    );
    await fillCreate();
    fireEvent.click(
      screen.getByRole("button", { name: "Review and create DRAFT" }),
    );
    confirm("Create DRAFT");
    expect(
      screen.getByRole("button", { name: "Saving Publication" }),
    ).toBeDisabled();
    expect(screen.getByLabelText("Publication title")).toBeDisabled();
    expect(api.create).toHaveBeenCalledTimes(1);
    await act(async () =>
      pending.resolve({
        ok: false,
        error: { kind: "unavailable", reason: "timeout" },
      }),
    );
    expect(
      screen.getByText("Creation outcome is uncertain"),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Publication title")).toBeDisabled();
    fireEvent.click(
      screen.getByRole("button", { name: "Retry same creation" }),
    );
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(2));
    expect(api.create.mock.calls[1]?.slice(0, 2)).toEqual(
      api.create.mock.calls[0]?.slice(0, 2),
    );
  });

  it("issues a fresh operation identity for changed settings after a definitive rejection", async () => {
    const api = client();
    api.create
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 400, code: "INVALID_REQUEST" },
      })
      .mockResolvedValueOnce({ ok: true, value: draft });
    render(
      <PublicationCreateScreen
        client={api}
        quizClient={quizzes()}
        source={source}
      />,
    );
    await fillCreate();
    fireEvent.click(
      screen.getByRole("button", { name: "Review and create DRAFT" }),
    );
    confirm("Create DRAFT");
    await screen.findByText("DRAFT was not confirmed");
    fireEvent.change(screen.getByLabelText("Publication title"), {
      target: { value: "Changed title" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Review and create DRAFT" }),
    );
    confirm("Create DRAFT");
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(2));
    expect(api.create.mock.calls[1]?.[1]).not.toBe(
      api.create.mock.calls[0]?.[1],
    );
    expect(api.create.mock.calls[1]?.[0].title).toBe("Changed title");
  });

  it("keeps an uncertain creation receipt locked even when a later retry is denied", async () => {
    const api = client();
    api.create
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "unavailable", reason: "timeout" },
      })
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 403, code: "ACCESS_DENIED" },
      })
      .mockResolvedValueOnce({ ok: true, value: draft });
    render(
      <PublicationCreateScreen
        client={api}
        quizClient={quizzes()}
        source={source}
      />,
    );
    await fillCreate();
    fireEvent.click(
      screen.getByRole("button", { name: "Review and create DRAFT" }),
    );
    confirm("Create DRAFT");
    fireEvent.click(
      await screen.findByRole("button", { name: "Retry same creation" }),
    );
    await screen.findByText(/You need the Teacher role and ownership/);
    expect(screen.getByLabelText("Publication title")).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Review and create DRAFT" }),
    ).toBeDisabled();
    fireEvent.click(
      screen.getByRole("button", { name: "Retry same creation" }),
    );
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(3));
    expect(api.create.mock.calls.map((call) => call.slice(0, 2))).toEqual(
      Array.from({ length: 3 }, () => api.create.mock.calls[0]?.slice(0, 2)),
    );
  });

  it("does not navigate on a creation response after unmount", async () => {
    const api = client();
    const pending = deferred<PublicationResult<OwnerView>>();
    api.create.mockReturnValue(pending.promise);
    const mounted = render(
      <PublicationCreateScreen
        client={api}
        quizClient={quizzes()}
        source={source}
      />,
    );
    await fillCreate();
    fireEvent.click(
      screen.getByRole("button", { name: "Review and create DRAFT" }),
    );
    confirm("Create DRAFT");
    const signal = api.create.mock.calls[0]?.[2];
    mounted.unmount();
    expect(signal?.aborted).toBe(true);
    await act(async () => pending.resolve({ ok: true, value: draft }));
    expect(navigation.push).not.toHaveBeenCalled();
  });
});

describe("Publication configuration form", () => {
  it("keeps CLASS monitoring explicitly unconfigured and clears forbidden fields when choosing PUBLIC", () => {
    const submit = vi.fn();
    render(
      <PublicationConfigurationForm
        initial={{
          ...configuration,
          audience: "CLASS",
          classroomId: id,
          monitoringChoice: "UNCONFIGURED",
        }}
        mode="edit"
        onSubmit={submit}
      />,
    );
    expect(
      screen.getByRole("radio", { name: "Choose later — DRAFT only" }),
    ).toBeChecked();
    fireEvent.click(screen.getByRole("radio", { name: "Public" }));
    expect(screen.queryByLabelText("Classroom ID")).not.toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "Review DRAFT changes" }),
    );
    confirm("Save DRAFT");
    expect(submit).toHaveBeenCalledWith(
      expect.objectContaining({
        audience: "PUBLIC",
        classroomId: null,
        monitoringChoice: "DISABLED",
        maxAttempts: 2,
      }),
    );
    fireEvent.click(screen.getByRole("radio", { name: "Classroom" }));
    expect(
      screen.getByRole("group", { name: "Choose one Classroom" }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("Classroom ID")).not.toBeInTheDocument();
    expect(
      screen.getByRole("radio", { name: "Choose later — DRAFT only" }),
    ).toBeChecked();
  });

  it("rejects empty duration, invalid chronology and a fractional duration without sending", () => {
    const submit = vi.fn();
    render(
      <PublicationConfigurationForm
        initial={configuration}
        mode="edit"
        onSubmit={submit}
      />,
    );
    fireEvent.change(screen.getByLabelText("Attempt duration (minutes)"), {
      target: { value: "" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Review DRAFT changes" }),
    );
    expect(
      screen.getByText("Check the highlighted fields"),
    ).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Attempt duration (minutes)"), {
      target: { value: "5.5" },
    });
    fireEvent.change(screen.getByLabelText("Participation end"), {
      target: { value: "2026-10-16T07:00:00.000Z" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Review DRAFT changes" }),
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled();
  });

  it("preserves an existing unlimited contract and maxAttempts without inventing editable policy", () => {
    const submit = vi.fn();
    render(
      <PublicationConfigurationForm
        initial={{ ...configuration, durationMinutes: 0, maxAttempts: 0 }}
        mode="edit"
        onSubmit={submit}
      />,
    );
    expect(
      screen.queryByLabelText("Attempt duration (minutes)"),
    ).not.toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "Review DRAFT changes" }),
    );
    confirm("Save DRAFT");
    expect(submit).toHaveBeenCalledWith(
      expect.objectContaining({ durationMinutes: 0, maxAttempts: 0 }),
    );
  });
});

describe("Authoritative Publication lifecycle", () => {
  it("blocks CLASS OPEN until monitoring is explicitly chosen", async () => {
    const api = client();
    api.get.mockResolvedValue({
      ok: true,
      value: {
        ...draft,
        configuration: {
          ...configuration,
          audience: "CLASS",
          classroomId: id,
          monitoringChoice: "UNCONFIGURED",
        },
        privateLocator: null,
      },
    });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    expect(
      await screen.findByRole("button", { name: "Review and OPEN" }),
    ).toBeDisabled();
    expect(
      screen.getByText("Choose a monitoring option first"),
    ).toBeInTheDocument();
    expect(api.open).not.toHaveBeenCalled();
  });

  it("preserves entered DRAFT fields across conflicts and requires explicit reload before new revision", async () => {
    const api = client();
    api.get
      .mockResolvedValueOnce({ ok: true, value: draft })
      .mockResolvedValue({
        ok: true,
        value: {
          ...draft,
          revision: 2,
          configuration: { ...configuration, title: "Other editor" },
        },
      });
    api.update.mockResolvedValue({
      ok: false,
      error: { kind: "api", status: 409, code: "PUBLICATION_CONFLICT" },
    });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit DRAFT" }));
    fireEvent.change(screen.getByLabelText("Publication title"), {
      target: { value: "Local unsaved title" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Review DRAFT changes" }),
    );
    confirm("Save DRAFT");
    await screen.findByText("Review the latest configuration");
    expect(screen.getByLabelText("Publication title")).toHaveValue(
      "Local unsaved title",
    );
    expect(
      screen.getByRole("button", { name: "Review DRAFT changes" }),
    ).toBeDisabled();
    expect(api.update.mock.calls[0]?.[1].expectedRevision).toBe(0);
    expect(api.update.mock.calls[0]?.[1].configuration.maxAttempts).toBe(2);
    fireEvent.click(
      screen.getByRole("button", {
        name: "Use the latest configuration (discard my edits)",
      }),
    );
    expect(screen.getByLabelText("Publication title")).toHaveValue(
      "Other editor",
    );
  });

  it("reconciles uncertain OPEN by GET without automatically replaying", async () => {
    const api = client();
    api.get
      .mockResolvedValueOnce({ ok: true, value: draft })
      .mockResolvedValueOnce({ ok: true, value: opened });
    api.open.mockResolvedValue({
      ok: false,
      error: { kind: "unavailable", reason: "network" },
    });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Review and OPEN" }),
    );
    confirm("OPEN Publication");
    await screen.findByText(
      "The latest details confirm the current state; nothing was repeated.",
    );
    expect(api.open).toHaveBeenCalledTimes(1);
    expect(api.open.mock.calls[0]?.[1]).toEqual({ expectedRevision: 0 });
    expect(api.get).toHaveBeenCalledTimes(2);
    expect(
      screen.getByRole("button", { name: "Review and CLOSE" }),
    ).toBeEnabled();
  });

  it("retries only the original OPEN revision after an authoritative unchanged read", async () => {
    const api = client();
    api.open
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "unavailable", reason: "timeout" },
      })
      .mockResolvedValueOnce({ ok: true, value: opened });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Review and OPEN" }),
    );
    confirm("OPEN Publication");
    const retry = await screen.findByRole("button", {
      name: "Retry",
    });
    await waitFor(() => expect(retry).toBeEnabled());
    fireEvent.click(retry);
    await screen.findByRole("button", { name: "Review and CLOSE" });
    expect(api.open.mock.calls.map((call) => call[1])).toEqual([
      { expectedRevision: 0 },
      { expectedRevision: 0 },
    ]);
  });

  it("defers reconciliation after an uncertain refresh to avoid automatic credential replay", async () => {
    const api = client();
    api.open.mockResolvedValue({
      ok: false,
      error: { kind: "unavailable", reason: "refresh" },
    });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Review and OPEN" }),
    );
    confirm("OPEN Publication");
    await screen.findByRole("button", { name: "Check now" });
    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.open).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Retry" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Check now" }));
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Retry" })).toBeEnabled(),
    );
    expect(
      screen.queryByRole("button", { name: "Review and CLOSE" }),
    ).not.toBeInTheDocument();
  });

  it("preserves extension inputs through revision conflicts until explicit discard", async () => {
    const api = client();
    api.get
      .mockResolvedValueOnce({ ok: true, value: opened })
      .mockResolvedValue({
        ok: true,
        value: {
          ...opened,
          revision: 3,
          configuration: {
            ...configuration,
            endAt: "2026-10-16T11:00:00Z",
            durationMinutes: 50,
          },
        },
      });
    api.extend.mockResolvedValue({
      ok: false,
      error: { kind: "api", status: 409, code: "REVISION_CONFLICT" },
    });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Extend participation schedule",
      }),
    );
    const extendedParts = localParts("2026-10-16T10:00:00Z");
    fireEvent.change(
      screen.getByLabelText("Extended participation end", { exact: true }),
      { target: { value: extendedParts.date } },
    );
    fireEvent.change(screen.getByLabelText("Extended participation end time"), {
      target: { value: extendedParts.time },
    });
    fireEvent.change(
      screen.getByLabelText("Extended Attempt duration (minutes)"),
      { target: { value: "45" } },
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Review schedule extension" }),
    );
    confirm("Save schedule extension");
    const discard = await screen.findByRole("button", {
      name: "Use the latest schedule (discard my extension)",
    });
    expect(
      screen.getByLabelText("Extended participation end", { exact: true }),
    ).toHaveValue(extendedParts.date);
    expect(
      screen.getByLabelText("Extended participation end time"),
    ).toHaveValue(extendedParts.time);
    expect(
      screen.getByLabelText("Extended Attempt duration (minutes)"),
    ).toHaveValue(45);
    expect(
      screen.getByRole("button", { name: "Review schedule extension" }),
    ).toBeDisabled();
    fireEvent.click(discard);
    const serverParts = localParts("2026-10-16T11:00:00Z");
    expect(
      screen.getByLabelText("Extended participation end", { exact: true }),
    ).toHaveValue(serverParts.date);
    expect(
      screen.getByLabelText("Extended participation end time"),
    ).toHaveValue(serverParts.time);
    expect(
      screen.getByLabelText("Extended Attempt duration (minutes)"),
    ).toHaveValue(50);
    expect(api.extend.mock.calls[0]?.[1].expectedRevision).toBe(1);
  });

  it("blocks decreasing schedule and duration mode changes, then sends only reviewed extension fields", async () => {
    const api = client();
    api.get.mockResolvedValue({ ok: true, value: opened });
    api.extend.mockResolvedValue({
      ok: true,
      value: {
        ...opened,
        revision: 2,
        configuration: {
          ...configuration,
          endAt: "2026-10-16T10:00:00Z",
          durationMinutes: 45,
        },
      },
    });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Extend participation schedule",
      }),
    );
    const decreaseParts = localParts("2026-10-16T08:30:00Z");
    fireEvent.change(
      screen.getByLabelText("Extended participation end", { exact: true }),
      { target: { value: decreaseParts.date } },
    );
    fireEvent.change(screen.getByLabelText("Extended participation end time"), {
      target: { value: decreaseParts.time },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Review schedule extension" }),
    );
    expect(
      screen.getByText(
        "Participation end cannot decrease and must resolve to a valid instant.",
      ),
    ).toBeInTheDocument();
    const extendedParts = localParts("2026-10-16T10:00:00Z");
    fireEvent.change(screen.getByLabelText("Extended participation end time"), {
      target: { value: extendedParts.time },
    });
    fireEvent.change(
      screen.getByLabelText("Extended Attempt duration (minutes)"),
      { target: { value: "0" } },
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Review schedule extension" }),
    );
    expect(
      screen.getByText(
        "Duration cannot decrease, exceed 240, or switch bounded/unlimited mode.",
      ),
    ).toBeInTheDocument();
    expect(api.extend).not.toHaveBeenCalled();
    fireEvent.change(
      screen.getByLabelText("Extended Attempt duration (minutes)"),
      { target: { value: "45" } },
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Review schedule extension" }),
    );
    confirm("Save schedule extension");
    await waitFor(() => expect(api.extend).toHaveBeenCalledTimes(1));
    expect(api.extend.mock.calls[0]?.[1]).toEqual({
      expectedRevision: 1,
      endAt: "2026-10-16T10:00:00.000Z",
      durationMinutes: 45,
    });
  });

  it("resubmits the authoritative end exactly when the field is untouched, preserving microseconds", async () => {
    const api = client();
    const microsecondEnd = {
      ...opened,
      configuration: {
        ...configuration,
        endAt: "2026-10-16T09:00:00.654321Z",
      },
    };
    api.get.mockResolvedValue({ ok: true, value: microsecondEnd });
    api.extend.mockResolvedValue({ ok: true, value: opened });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Extend participation schedule",
      }),
    );
    fireEvent.change(
      screen.getByLabelText("Extended Attempt duration (minutes)"),
      { target: { value: "45" } },
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Review schedule extension" }),
    );
    confirm("Save schedule extension");
    await waitFor(() => expect(api.extend).toHaveBeenCalledTimes(1));
    expect(api.extend.mock.calls[0]?.[1]).toEqual({
      expectedRevision: 1,
      endAt: "2026-10-16T09:00:00.654321Z",
      durationMinutes: 45,
    });
  });

  it("rejects a re-entered same-minute end that would shorten a microsecond end", async () => {
    const api = client();
    const microsecondEnd = {
      ...opened,
      configuration: {
        ...configuration,
        endAt: "2026-10-16T09:00:00.654321Z",
      },
    };
    api.get.mockResolvedValue({ ok: true, value: microsecondEnd });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Extend participation schedule",
      }),
    );
    // Re-entering the same displayed wall clock emits a millisecond instant,
    // which is strictly earlier than the authoritative microsecond end.
    // (React dedupes identical change events, so nudge away and back.)
    const sameParts = localParts("2026-10-16T09:00:00Z");
    fireEvent.change(screen.getByLabelText("Extended participation end time"), {
      target: { value: "12:34" },
    });
    fireEvent.change(screen.getByLabelText("Extended participation end time"), {
      target: { value: sameParts.time },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Review schedule extension" }),
    );
    expect(
      screen.getByText(
        "Participation end cannot decrease and must resolve to a valid instant.",
      ),
    ).toBeInTheDocument();
    expect(api.extend).not.toHaveBeenCalled();
  });

  it("renders ACTIVE_ATTEMPTS honestly and keeps OPEN until a minimal server CLOSE acknowledgement", async () => {
    const api = client();
    api.get.mockResolvedValue({ ok: true, value: opened });
    api.close
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 409, code: "ACTIVE_ATTEMPTS" },
      })
      .mockResolvedValueOnce({
        ok: true,
        value: {
          publicationId: id,
          state: "CLOSED",
          revision: 2,
          closedAt: "2026-10-16T08:30:00Z",
        },
      });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Review and CLOSE" }),
    );
    confirm("CLOSE Publication");
    await screen.findByText(/Active attempts prevent closing/);
    expect(screen.queryByText("Assessment closed")).not.toBeInTheDocument();
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Review and CLOSE" }),
      ).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "Review and CLOSE" }));
    confirm("CLOSE Publication");
    await screen.findByText("Assessment closed");
    expect(api.close).toHaveBeenCalledTimes(2);
    expect(
      screen.queryByRole("button", { name: "Review and CLOSE" }),
    ).not.toBeInTheDocument();
  });

  it("retains identical CLOSE command and key after a lost response with unchanged GET", async () => {
    const api = client();
    api.get.mockResolvedValue({ ok: true, value: opened });
    api.close
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "unavailable", reason: "network" },
      })
      .mockResolvedValueOnce({
        ok: true,
        value: {
          publicationId: id,
          state: "CLOSED",
          revision: 2,
          closedAt: "2026-10-16T08:30:00Z",
        },
      });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Review and CLOSE" }),
    );
    confirm("CLOSE Publication");
    const retry = await screen.findByRole("button", {
      name: "Retry",
    });
    await waitFor(() => expect(retry).toBeEnabled());
    expect(api.close).toHaveBeenCalledTimes(1);
    fireEvent.click(retry);
    await screen.findByText("Assessment closed");
    expect(api.close.mock.calls[1]?.slice(0, 3)).toEqual(
      api.close.mock.calls[0]?.slice(0, 3),
    );
  });

  it("retains identical CLOSE command and key after capacity throttling", async () => {
    const api = client();
    api.get.mockResolvedValue({ ok: true, value: opened });
    api.close
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 429, code: "CAPACITY_EXCEEDED" },
      })
      .mockResolvedValueOnce({
        ok: true,
        value: {
          publicationId: id,
          state: "CLOSED",
          revision: 2,
          closedAt: "2026-10-16T08:30:00Z",
        },
      });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Review and CLOSE" }),
    );
    confirm("CLOSE Publication");
    expect(await screen.findByText("Retrying safely")).toBeInTheDocument();
    const retry = screen.getByRole("button", {
      name: "Retry",
    });
    await waitFor(() => expect(retry).toBeEnabled());
    fireEvent.click(retry);
    await screen.findByText("Assessment closed");
    expect(api.close.mock.calls[1]?.slice(0, 3)).toEqual(
      api.close.mock.calls[0]?.slice(0, 3),
    );
  });

  it("clears a throttled CLOSE retry after a revision conflict reconciles newer OPEN state", async () => {
    const newer = { ...opened, revision: 2 };
    const api = client();
    api.get
      .mockResolvedValueOnce({ ok: true, value: opened })
      .mockResolvedValueOnce({ ok: true, value: opened })
      .mockResolvedValueOnce({ ok: true, value: newer });
    api.close
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 429, code: "CAPACITY_EXCEEDED" },
      })
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 409, code: "REVISION_CONFLICT" },
      });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Review and CLOSE" }),
    );
    confirm("CLOSE Publication");
    const retry = await screen.findByRole("button", {
      name: "Retry",
    });
    await waitFor(() => expect(retry).toBeEnabled());
    fireEvent.click(retry);
    await waitFor(() =>
      expect(screen.getAllByText(/Version 3/).length).toBeGreaterThan(0),
    );
    expect(
      screen.queryByRole("button", { name: "Retry" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Review and CLOSE" }),
    ).toBeEnabled();
    expect(api.close.mock.calls[1]?.slice(0, 3)).toEqual(
      api.close.mock.calls[0]?.slice(0, 3),
    );
  });

  it("recognizes durable CLOSED from GET after a lost response without replaying CLOSE", async () => {
    const api = client();
    api.get
      .mockResolvedValueOnce({ ok: true, value: opened })
      .mockResolvedValueOnce({
        ok: true,
        value: { ...opened, state: "CLOSED", revision: 2 },
      });
    api.close.mockResolvedValue({
      ok: false,
      error: { kind: "unavailable", reason: "timeout" },
    });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Review and CLOSE" }),
    );
    confirm("CLOSE Publication");
    await screen.findByText("Assessment closed");
    expect(api.close).toHaveBeenCalledTimes(1);
    expect(
      screen.queryByRole("button", { name: "Retry" }),
    ).not.toBeInTheDocument();
  });

  it("keeps the private locator masked and only copies or reveals on explicit action", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });
    renderDetail(
      <PublicationDetailScreen client={client()} publicationId={id} />,
    );
    await screen.findByRole("heading", { name: configuration.title });
    expect(screen.queryByText(locator)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Copy locator" }));
    await screen.findByText("Copied.");
    expect(writeText).toHaveBeenCalledWith(locator);
    fireEvent.click(screen.getByRole("button", { name: "Show locator" }));
    expect(screen.getByText(locator)).toBeInTheDocument();
    expect(
      Array.from(document.querySelectorAll("a")).some((link) =>
        link.href.includes(locator),
      ),
    ).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Hide locator" }));
    expect(screen.queryByText(locator)).not.toBeInTheDocument();
  });

  it("hiding a dirty schedule extension confirms; cancel keeps values, accept hides", async () => {
    const api = client();
    api.get.mockResolvedValue({ ok: true, value: opened });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Extend participation schedule",
      }),
    );
    const duration = screen.getByLabelText(
      "Extended Attempt duration (minutes)",
    );
    fireEvent.change(duration, { target: { value: "45" } });
    const confirmSpy = vi
      .spyOn(window, "confirm")
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true);
    fireEvent.click(
      screen.getByRole("button", { name: "Hide schedule extension" }),
    );
    // Cancel: the extension stays open with the entered value intact.
    expect(confirmSpy).toHaveBeenCalledTimes(1);
    expect(
      screen.getByRole("button", { name: "Hide schedule extension" }),
    ).toBeInTheDocument();
    expect(
      screen.getByLabelText("Extended Attempt duration (minutes)"),
    ).toHaveValue(45);
    fireEvent.click(
      screen.getByRole("button", { name: "Hide schedule extension" }),
    );
    // Accept: the extension is hidden.
    expect(confirmSpy).toHaveBeenCalledTimes(2);
    expect(
      screen.getByRole("button", {
        name: "Extend participation schedule",
      }),
    ).toBeInTheDocument();
    expect(
      screen.queryByLabelText("Extended Attempt duration (minutes)"),
    ).not.toBeInTheDocument();
    confirmSpy.mockRestore();
  });

  it("hiding a clean schedule extension never asks for confirmation", async () => {
    const api = client();
    api.get.mockResolvedValue({ ok: true, value: opened });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Extend participation schedule",
      }),
    );
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    fireEvent.click(
      screen.getByRole("button", { name: "Hide schedule extension" }),
    );
    expect(confirmSpy).not.toHaveBeenCalled();
    expect(
      screen.getByRole("button", {
        name: "Extend participation schedule",
      }),
    ).toBeInTheDocument();
    confirmSpy.mockRestore();
  });

  it("a dirty schedule extension registers unsaved work for workspace switches", async () => {
    const api = client();
    api.get.mockResolvedValue({ ok: true, value: opened });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Extend participation schedule",
      }),
    );
    // Clean extension: leaving is allowed without any confirmation.
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    expect(confirmUnsavedWorkLeaving()).toBe(true);
    expect(confirmSpy).not.toHaveBeenCalled();
    // Dirty extension: the registry blocks the leave and the switch must not
    // change workspace state or URL.
    fireEvent.change(
      screen.getByLabelText("Extended Attempt duration (minutes)"),
      { target: { value: "45" } },
    );
    expect(confirmUnsavedWorkLeaving()).toBe(false);
    expect(confirmSpy).toHaveBeenCalledTimes(1);
    confirmSpy.mockRestore();
  });

  it("a revision-conflicted (disabled) DRAFT form remains protected as unsaved work", () => {
    // The form is disabled exactly as a revision conflict or blocked state
    // does; entered values must still be protected from silent discard.
    render(
      <PublicationConfigurationForm
        disabled
        initial={configuration}
        mode="edit"
        onSubmit={() => {}}
      />,
    );
    fireEvent.change(screen.getByLabelText("Publication title"), {
      target: { value: "Unsaved conflicting edit" },
    });
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    expect(confirmUnsavedWorkLeaving()).toBe(false);
    expect(confirmSpy).toHaveBeenCalledTimes(1);
    confirmSpy.mockRestore();
  });

  it("a clean form does not request leave confirmation", () => {
    render(
      <PublicationConfigurationForm
        initial={configuration}
        mode="edit"
        onSubmit={() => {}}
      />,
    );
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    expect(confirmUnsavedWorkLeaving()).toBe(true);
    expect(confirmSpy).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });
});

describe("Publication detail results-first layout (UX-04)", () => {
  it("shows compact chips, a keyboard-toggleable configuration summary and an embedded Results section for OPEN", async () => {
    const api = client();
    api.get.mockResolvedValue({ ok: true, value: opened });
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    // Lifecycle controls remain present with identical labels.
    expect(
      await screen.findByRole("button", { name: "Review and CLOSE" }),
    ).toBeInTheDocument();
    // Compact configuration chips are visible outside the disclosure; their
    // "label:" form (with colon) only occurs in the chip row.
    expect(screen.getByText("Attempt duration:")).toBeInTheDocument();
    expect(screen.getByText("Maximum Attempts:")).toBeInTheDocument();
    expect(screen.getByText("Audience:")).toBeInTheDocument();
    expect(screen.getByText("Monitoring:")).toBeInTheDocument();
    // Collapsed by default (jsdom exposes no matchMedia): the disclosure
    // keeps the full authoritative summary closed until it is toggled.
    const disclosure = screen
      .getByText("Configuration summary")
      .closest("details");
    expect(disclosure).not.toBeNull();
    expect(disclosure).not.toHaveAttribute("open");
    fireEvent.click(screen.getByText("Configuration summary"));
    expect(disclosure).toHaveAttribute("open");
    fireEvent.click(screen.getByText("Configuration summary"));
    expect(disclosure).not.toHaveAttribute("open");
    // Results section is embedded and fetches its own rows.
    expect(
      screen.getByRole("heading", { name: "Results" }),
    ).toBeInTheDocument();
    expect(screen.getByText("No results yet")).toBeInTheDocument();
    expect(resultsClient.results).toHaveBeenCalledWith(
      id,
      undefined,
      expect.any(AbortSignal),
    );
    // Results-first ordering: the embedded Results section (main column)
    // precedes the sidebar configuration summary and lifecycle actions.
    const summaryToggle = screen.getByText("Configuration summary");
    const closeControl = screen.getByRole("button", {
      name: "Review and CLOSE",
    });
    const resultsHeading = screen.getByRole("heading", { name: "Results" });
    expect(
      resultsHeading.compareDocumentPosition(summaryToggle) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      summaryToggle.compareDocumentPosition(closeControl) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("keeps the DRAFT detail management-first with the full configuration and no embedded results", async () => {
    const api = client();
    renderDetail(<PublicationDetailScreen client={api} publicationId={id} />);
    expect(
      await screen.findByRole("heading", {
        name: "Configuration",
      }),
    ).toBeInTheDocument();
    expect(screen.getByText("Source version")).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "Results" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("No results yet")).not.toBeInTheDocument();
    expect(resultsClient.results).not.toHaveBeenCalled();
  });
});
