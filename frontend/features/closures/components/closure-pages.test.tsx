import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { PublicationApiClient } from "../../assessments/api/publication-api-client";
import type {
  ClosureView,
  Configuration,
  OwnerView,
} from "../../assessments/api/publication-contract.generated";
import type { ClosureApiClient } from "../api/closure-api-client";
import { ClosureDetailScreen, ClosureManagementScreen } from "./closure-pages";

const navigation = vi.hoisted(() => ({ push: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => navigation }));

const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const closureRequestId = "f7b14962-3a2e-4d2d-926a-3b36ea90c198";
const teacherId = "c7b14962-3a2e-4d2d-926a-3b36ea90c195";
const configuration: Configuration = {
  title: "Server-authoritative closure",
  description: null,
  quizId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  quizVersionId: "9bd4c564-3c27-4e6d-91aa-a004334aa8f7",
  versionNumber: 3,
  audience: "PUBLIC",
  classroomId: null,
  startAt: "2026-10-16T08:00:00Z",
  endAt: "2026-10-16T09:00:00Z",
  durationMinutes: 0,
  monitoringChoice: "DISABLED",
  maxAttempts: 2,
};
const opened: OwnerView = {
  publicationId,
  configuration,
  state: "OPEN",
  revision: 4,
  createdAt: "2026-10-16T07:00:00Z",
  openedAt: "2026-10-16T07:30:00Z",
  privateLocator: "A".repeat(43),
};
const pendingView = {
  closureRequestId,
  publicationId,
  requestingTeacherId: teacherId,
  requestedAt: "2026-10-16T09:01:00Z",
  state: "PENDING" as const,
  finalizationStartedAt: null,
  resolvedAt: null,
  publicationRevision: 5,
};

function publications(...reads: OwnerView[]): PublicationApiClient {
  return {
    list: vi.fn(),
    create: vi.fn(),
    get: vi.fn<PublicationApiClient["get"]>().mockImplementation(async () => ({
      ok: true,
      value: reads.shift() ?? opened,
    })),
    update: vi.fn(),
    open: vi.fn(),
    extend: vi.fn(),
    close: vi.fn(),
  };
}

function closures(): ClosureApiClient {
  return {
    request: vi.fn(),
    get: vi.fn(),
    cancel: vi.fn(),
    complete: vi.fn(),
  };
}

function confirm(phrase: string, label: string) {
  const dialog = screen.getByRole("dialog");
  const button = within(dialog).getByRole("button", { name: label });
  expect(button).toBeDisabled();
  fireEvent.change(
    within(dialog).getByLabelText(`Type ${phrase} to enable this action`),
    { target: { value: phrase } },
  );
  expect(button).toBeEnabled();
  fireEvent.click(button);
}

beforeEach(() => {
  navigation.push.mockClear();
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

describe("Teacher closure management", () => {
  it("lets the server decide endAt eligibility and sends a safe integer revision", async () => {
    const publicationClient = publications(opened, opened);
    const closureClient = closures();
    vi.mocked(closureClient.request).mockResolvedValue({
      ok: true,
      status: 201,
      value: { view: pendingView, created: true },
    });
    render(
      <ClosureManagementScreen
        closureClient={closureClient}
        publicationClient={publicationClient}
        publicationId={publicationId}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Review and REQUEST closure",
      }),
    );
    expect(
      screen.getByText(/can block closure indefinitely/),
    ).toBeInTheDocument();
    confirm("REQUEST", "REQUEST delayed closure");
    await waitFor(() => expect(closureClient.request).toHaveBeenCalledTimes(1));
    expect(closureClient.request).toHaveBeenCalledWith(
      publicationId,
      { expectedRevision: 4 },
      expect.stringMatching(/^[A-Za-z0-9_-]{16,128}$/),
      expect.any(AbortSignal),
    );
    expect(
      await screen.findByText(/Publication is NOT closed/),
    ).toBeInTheDocument();
  });

  it("keeps a foreign Teacher's masked Publication denial free of closure actions", async () => {
    const publicationClient = publications();
    vi.mocked(publicationClient.get).mockResolvedValue({
      ok: false,
      error: { kind: "api", status: 404, code: "PUBLICATION_NOT_FOUND" },
    });
    const closureClient = closures();
    render(
      <ClosureManagementScreen
        closureClient={closureClient}
        publicationClient={publicationClient}
        publicationId={publicationId}
      />,
    );
    expect(
      await screen.findByText(/unavailable or you do not have permission/),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Review and REQUEST closure" }),
    ).not.toBeInTheDocument();
    expect(closureClient.request).not.toHaveBeenCalled();
  });

  it("reconciles a stale expectedRevision conflict before another action", async () => {
    const current = { ...opened, revision: 5 };
    const publicationClient = publications(opened, current);
    const closureClient = closures();
    vi.mocked(closureClient.request).mockResolvedValue({
      ok: false,
      error: { kind: "api", status: 409, code: "REVISION_CONFLICT" },
    });
    render(
      <ClosureManagementScreen
        closureClient={closureClient}
        publicationClient={publicationClient}
        publicationId={publicationId}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Review and REQUEST closure",
      }),
    );
    confirm("REQUEST", "REQUEST delayed closure");
    await screen.findByText("Revision 5");
    expect(publicationClient.get).toHaveBeenCalledTimes(2);
    expect(
      screen.queryByRole("button", { name: "Retry original request" }),
    ).not.toBeInTheDocument();
  });

  it("retains the exact payload and key for an explicit uncertain replay", async () => {
    const publicationClient = publications(opened, opened, opened);
    const closureClient = closures();
    vi.mocked(closureClient.request)
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "unavailable", reason: "network" },
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        value: { view: pendingView, created: false },
      });
    render(
      <ClosureManagementScreen
        closureClient={closureClient}
        publicationClient={publicationClient}
        publicationId={publicationId}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Review and REQUEST closure",
      }),
    );
    confirm("REQUEST", "REQUEST delayed closure");
    const retry = await screen.findByRole("button", {
      name: "Retry original request",
    });
    await waitFor(() => expect(retry).toBeEnabled());
    fireEvent.click(retry);
    await screen.findByText("Existing closure request");
    expect(closureClient.request).toHaveBeenCalledTimes(2);
    expect(vi.mocked(closureClient.request).mock.calls[1]?.slice(0, 3)).toEqual(
      vi.mocked(closureClient.request).mock.calls[0]?.slice(0, 3),
    );
  });

  it("retains the exact command identity when capacity throttling permits a retry", async () => {
    const publicationClient = publications(opened, opened, opened);
    const closureClient = closures();
    vi.mocked(closureClient.request)
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 429, code: "CAPACITY_EXCEEDED" },
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        value: { view: pendingView, created: false },
      });
    render(
      <ClosureManagementScreen
        closureClient={closureClient}
        publicationClient={publicationClient}
        publicationId={publicationId}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Review and REQUEST closure",
      }),
    );
    confirm("REQUEST", "REQUEST delayed closure");
    const retry = await screen.findByRole("button", {
      name: "Retry original request",
    });
    await waitFor(() => expect(retry).toBeEnabled());
    fireEvent.click(retry);
    await screen.findByText("Existing closure request");
    expect(vi.mocked(closureClient.request).mock.calls[1]?.slice(0, 3)).toEqual(
      vi.mocked(closureClient.request).mock.calls[0]?.slice(0, 3),
    );
  });
});

describe("Teacher closure request detail", () => {
  it("preserves a COMPLETE reservation across CLOSURE_NOT_READY and retries the same key", async () => {
    const closureClient = closures();
    vi.mocked(closureClient.get).mockResolvedValue({
      ok: true,
      status: 200,
      value: pendingView,
    });
    vi.mocked(closureClient.complete)
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 409, code: "CLOSURE_NOT_READY" },
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 202,
        value: {
          closureRequestId,
          publicationId,
          closed: false,
          publicationRevision: 5,
        },
      });
    render(
      <ClosureDetailScreen
        closureClient={closureClient}
        closureRequestId={closureRequestId}
        publicationId={publicationId}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Review and COMPLETE closure",
      }),
    );
    confirm("COMPLETE", "COMPLETE closure");
    const retry = await screen.findByRole("button", {
      name: "Retry original command",
    });
    await waitFor(() => expect(retry).toBeEnabled());
    expect(closureClient.get).toHaveBeenCalledTimes(2);
    fireEvent.click(retry);
    await screen.findByText("Bounded progress committed");
    expect(
      vi.mocked(closureClient.complete).mock.calls[1]?.slice(0, 4),
    ).toEqual(vi.mocked(closureClient.complete).mock.calls[0]?.slice(0, 4));
  });

  it("reconciles a CANCEL race to FINALIZING and does not replay a losing cancel", async () => {
    const finalizing = {
      ...pendingView,
      state: "FINALIZING" as const,
      finalizationStartedAt: "2026-10-18T08:32:00Z",
    };
    const closureClient = closures();
    vi.mocked(closureClient.get)
      .mockResolvedValueOnce({ ok: true, status: 200, value: pendingView })
      .mockResolvedValueOnce({ ok: true, status: 200, value: finalizing });
    vi.mocked(closureClient.cancel).mockResolvedValue({
      ok: false,
      error: {
        kind: "api",
        status: 409,
        code: "CLOSURE_FINALIZATION_STARTED",
      },
    });
    render(
      <ClosureDetailScreen
        closureClient={closureClient}
        closureRequestId={closureRequestId}
        publicationId={publicationId}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Review and CANCEL request",
      }),
    );
    confirm("CANCEL", "CANCEL closure request");
    await screen.findByText("Finalizing");
    expect(closureClient.get).toHaveBeenCalledTimes(2);
    expect(
      screen.queryByRole("button", { name: "Retry original command" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Review and CANCEL request" }),
    ).toBeDisabled();
  });

  it.each([
    ["CANCELLED", "Cancelled", null],
    ["COMPLETED", "Completed", "2026-10-18T08:32:00Z"],
    ["RESOLVED_EARLY", "Resolved early", null],
  ] as const)(
    "renders resolved %s lifecycle details without mutation controls",
    async (state, label, finalizationStartedAt) => {
      const closureClient = closures();
      const resolved: ClosureView = {
        ...pendingView,
        state,
        finalizationStartedAt,
        resolvedAt: "2026-10-18T08:35:00Z",
      };
      vi.mocked(closureClient.get).mockResolvedValue({
        ok: true,
        status: 200,
        value: resolved,
      });
      render(
        <ClosureDetailScreen
          closureClient={closureClient}
          closureRequestId={closureRequestId}
          publicationId={publicationId}
        />,
      );
      expect(
        await screen.findByText(label, { exact: true }),
      ).toBeInTheDocument();
      expect(screen.getByText("18 Oct 2026, 15:35")).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: /Review and (COMPLETE|CANCEL)/ }),
      ).not.toBeInTheDocument();
      expect(screen.getByText("This request is resolved")).toBeInTheDocument();
    },
  );
});
