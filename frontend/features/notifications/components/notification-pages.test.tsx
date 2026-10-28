import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { Notification } from "../../assessments/api/publication-contract.generated";
import type { NotificationApiClient } from "../api/notification-api-client";
import {
  InboxScreen,
  NotificationDetailScreen,
  studentAttemptHref,
} from "./notification-pages";

const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const closureRequestId = "f7b14962-3a2e-4d2d-926a-3b36ea90c198";
const attemptId = "a7b14962-3a2e-4d2d-926a-3b36ea90c197";
const notificationId = "d7b14962-3a2e-4d2d-926a-3b36ea90c194";

const preparing: Notification = {
  notificationId,
  publicationId,
  publicationTitle: "Durable recipient notice",
  attemptId,
  closureRequestId,
  teacherInitiated: true,
  contentVersion: 1,
  publishedAt: "2026-10-16T08:31:00Z",
  confirmedNoticeAt: null,
  closureCutoffAt: null,
  warningHours: 48,
  noticeStatus: "PREPARING",
  message: "Your notice is being prepared.",
  resultId: null,
  serverNow: "2026-10-16T08:31:30Z",
};

const confirmed: Notification = {
  ...preparing,
  confirmedNoticeAt: "2026-10-16T08:32:00Z",
  closureCutoffAt: "2026-10-18T08:32:00Z",
  noticeStatus: "NOTICE_CONFIRMED",
  message:
    "Your Teacher requested closure. Saved answers may be finalized at the confirmed cutoff.",
  serverNow: "2026-10-16T09:32:00Z",
};

function client(): NotificationApiClient {
  return { list: vi.fn(), get: vi.fn() };
}

describe("Student notification inbox", () => {
  it("leaves loading, renders recipient history and paginates with the opaque cursor", async () => {
    const api = client();
    const second: Notification = {
      ...preparing,
      notificationId: "c7b14962-3a2e-4d2d-926a-3b36ea90c190",
      publicationTitle: "Older retained notice",
      noticeStatus: "CANCELLED",
      message: "Closure was cancelled.",
    };
    vi.mocked(api.list)
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        value: { items: [confirmed], nextCursor: "opaque.page_2" },
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        value: { items: [second], nextCursor: null },
      });
    render(<InboxScreen client={api} />);

    expect(
      await screen.findByRole("heading", { name: confirmed.publicationTitle }),
    ).toBeInTheDocument();
    expect(screen.queryByText("Loading your assessment notices…")).toBeNull();
    expect(screen.getByText("48-hour period confirmed")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Load more notices" }));
    expect(
      await screen.findByRole("heading", { name: second.publicationTitle }),
    ).toBeInTheDocument();
    expect(api.list).toHaveBeenNthCalledWith(
      2,
      { cursor: "opaque.page_2" },
      expect.any(AbortSignal),
    );
  });

  it("shows a reachable empty state instead of remaining in loading", async () => {
    const api = client();
    vi.mocked(api.list).mockResolvedValue({
      ok: true,
      status: 200,
      value: { items: [], nextCursor: null },
    });
    render(<InboxScreen client={api} />);
    expect(
      await screen.findByRole("heading", { name: "No assessment notices yet" }),
    ).toBeInTheDocument();
  });

  it("renders cancelled, submitted, finalized and early-resolution history without read state", async () => {
    const api = client();
    const states: Notification[] = [
      {
        ...preparing,
        notificationId: "17b14962-3a2e-4d2d-926a-3b36ea90c191",
        noticeStatus: "CANCELLED",
      },
      {
        ...confirmed,
        notificationId: "27b14962-3a2e-4d2d-926a-3b36ea90c192",
        noticeStatus: "SUBMITTED",
      },
      {
        ...confirmed,
        notificationId: "37b14962-3a2e-4d2d-926a-3b36ea90c193",
        noticeStatus: "FINALIZED",
      },
      {
        ...preparing,
        notificationId: "47b14962-3a2e-4d2d-926a-3b36ea90c194",
        noticeStatus: "RESOLVED_EARLY",
      },
    ];
    vi.mocked(api.list).mockResolvedValue({
      ok: true,
      status: 200,
      value: { items: states, nextCursor: null },
    });
    render(<InboxScreen client={api} />);
    expect(await screen.findByText("Closure cancelled")).toBeInTheDocument();
    expect(screen.getByText("You submitted")).toBeInTheDocument();
    expect(screen.getByText("Finalized by system")).toBeInTheDocument();
    expect(screen.getByText("Resolved early")).toBeInTheDocument();
    expect(screen.queryByText(/unread/i)).not.toBeInTheDocument();
  });

  it("does not create a cutoff or acknowledgment for PREPARING", async () => {
    const api = client();
    vi.mocked(api.get).mockResolvedValue({
      ok: true,
      status: 200,
      value: preparing,
    });
    render(
      <NotificationDetailScreen client={api} notificationId={notificationId} />,
    );
    expect(
      await screen.findAllByText("Waiting for notice confirmation"),
    ).toHaveLength(2);
    expect(screen.getByText(/there is no cutoff/)).toBeInTheDocument();
    expect(screen.queryByText(/acknowledge/i)).not.toBeInTheDocument();
  });

  it("renders exact confirmed timestamps and refreshes evolving server status", async () => {
    const api = client();
    const finalized: Notification = {
      ...confirmed,
      noticeStatus: "FINALIZED",
      resultId: "e7b14962-3a2e-4d2d-926a-3b36ea90c193",
      serverNow: "2026-10-18T08:33:00Z",
    };
    vi.mocked(api.get)
      .mockResolvedValueOnce({ ok: true, status: 200, value: confirmed })
      .mockResolvedValueOnce({ ok: true, status: 200, value: finalized });
    render(
      <NotificationDetailScreen client={api} notificationId={notificationId} />,
    );
    expect(
      await screen.findByText("48-hour period confirmed"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("16 Oct 2026, 15:32 (Vietnam time, UTC+7)"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("18 Oct 2026, 15:32 (Vietnam time, UTC+7)"),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Refresh notice" }));
    expect(await screen.findByText("Finalized by system")).toBeInTheDocument();
    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it("keeps inaccessible notices masked and displays no stale recipient content", async () => {
    const api = client();
    vi.mocked(api.get).mockResolvedValue({
      ok: false,
      error: { kind: "api", status: 404, code: "NOTIFICATION_NOT_FOUND" },
    });
    render(
      <NotificationDetailScreen client={api} notificationId={notificationId} />,
    );
    expect(
      await screen.findByText(
        /unavailable or not accessible for the current account/,
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(preparing.message)).not.toBeInTheDocument();
  });

  it("links the recipient-authorized notice to the real Attempt route", async () => {
    const api = client();
    vi.mocked(api.get).mockResolvedValue({
      ok: true,
      status: 200,
      value: preparing,
    });
    render(
      <NotificationDetailScreen client={api} notificationId={notificationId} />,
    );
    expect(studentAttemptHref(preparing)).toBe(`/app/attempts/${attemptId}`);
    expect(
      await screen.findByRole("link", {
        name: attemptId,
      }),
    ).toHaveAttribute("href", `/app/attempts/${attemptId}`);
  });
});
