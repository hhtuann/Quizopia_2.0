import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useRef, useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { Button } from "../../../components/ui/button";
import type {
  AuthenticatedRequestExecutor,
  ReplayableAuthenticatedRequest,
} from "../../../lib/api/authenticated-request";
import type { HttpResponse } from "../../../lib/api/http-transport";
import { createQuizApiClient } from "../api/quiz-api-client";
import { PublishedVersionHistory } from "./published-version-history";

const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";

function response(status: number, value: unknown): HttpResponse {
  return {
    body: { kind: "json", value },
    headers: new Headers({ "content-type": "application/json" }),
    ok: status >= 200 && status < 300,
    status,
  };
}

function summary(versionNumber: number) {
  return {
    id:
      versionNumber === 1
        ? "e7b14962-3a2e-4d2d-926a-3b36ea90c199"
        : "f87b6d86-c26d-47fe-83db-cce701112233",
    quizId,
    versionNumber,
    titleSnapshot: `Snapshot ${versionNumber}`,
    descriptionSnapshot: `Description ${versionNumber}`,
    contentSchemaVersion: 1,
    createdAt: `2026-10-08T0${versionNumber}:30:00Z`,
  };
}

function detail(versionNumber: number) {
  return {
    ...summary(versionNumber),
    sourceSnapshot: `Câu 1 [SINGLE_CHOICE]: Version ${versionNumber} question\n*A. correct ${versionNumber}\nB. b\nC. c\nD. d`,
    structuredContent: {
      questions: [
        {
          number: 1,
          type: "SINGLE_CHOICE",
          stemMarkdown: `Version ${versionNumber} question`,
          options: [
            { label: "A", markdown: "correct", correct: true },
            { label: "B", markdown: "b", correct: false },
            { label: "C", markdown: "c", correct: false },
            { label: "D", markdown: "d", correct: false },
          ],
          numericAnswer: null,
          explanationMarkdown: null,
        },
      ],
    },
  };
}

function executorWith(
  handler: (request: ReplayableAuthenticatedRequest) => Promise<HttpResponse>,
) {
  const execute = vi.fn(async (request: ReplayableAuthenticatedRequest) => ({
    kind: "response" as const,
    response: await handler(request),
  }));
  const executor: AuthenticatedRequestExecutor = {
    execute,
    executeOnce: vi.fn(),
    executeOnceWithAccessToken: vi.fn(),
  };
  return { execute, executor };
}

function HistoryHarness({
  executor,
  quizIdValue = quizId,
  refreshVersion = 0,
}: {
  readonly executor: AuthenticatedRequestExecutor;
  readonly quizIdValue?: string;
  readonly refreshVersion?: number;
}) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const client = createQuizApiClient({ authenticatedRequests: executor });
  return (
    <>
      <Button onClick={() => setOpen(true)} ref={triggerRef}>
        Published versions
      </Button>
      <PublishedVersionHistory
        client={client}
        isOpen={open}
        onClose={() => setOpen(false)}
        quizId={quizIdValue}
        refreshVersion={refreshVersion}
        triggerRef={triggerRef}
      />
    </>
  );
}

describe("PublishedVersionHistory", () => {
  it("announces initial history loading before the first page resolves", async () => {
    let releaseHistory: ((value: HttpResponse) => void) | undefined;
    const pending = new Promise<HttpResponse>((resolve) => {
      releaseHistory = resolve;
    });
    const { executor } = executorWith(async () => pending);
    render(<HistoryHarness executor={executor} />);

    fireEvent.click(screen.getByRole("button", { name: "Published versions" }));
    expect(screen.getByText("Loading versions...")).toBeInTheDocument();
    releaseHistory?.(response(200, { items: [], nextCursor: null }));
    expect(
      await screen.findByRole("heading", { name: "No published versions yet" }),
    ).toBeInTheDocument();
  });

  it("loads metadata first, then renders the selected snapshot as read-only and restores focus", async () => {
    const targets: string[] = [];
    const { executor } = executorWith(async (request) => {
      const target = String(request.createRequest().target);
      targets.push(target);
      return target.includes("/versions?")
        ? response(200, { items: [summary(2), summary(1)], nextCursor: null })
        : response(200, detail(2));
    });
    render(<HistoryHarness executor={executor} />);

    const trigger = screen.getByRole("button", { name: "Published versions" });
    fireEvent.click(trigger);
    expect(
      await screen.findByRole("button", { name: /Version 2/ }),
    ).toBeInTheDocument();
    const closeButton = screen.getByRole("button", {
      name: "Close published versions",
    });
    expect(
      screen.queryByRole("button", { name: "Current draft" }),
    ).not.toBeInTheDocument();
    expect(closeButton).toHaveClass("h-11", "w-11", "p-0");
    expect(closeButton.querySelector("svg")).toBeInTheDocument();
    await waitFor(() => expect(closeButton).toHaveFocus());
    fireEvent.keyDown(screen.getByRole("dialog"), {
      key: "Tab",
      shiftKey: true,
    });
    expect(screen.getByRole("button", { name: /Version 1/ })).toHaveFocus();
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Tab" });
    expect(closeButton).toHaveFocus();
    expect(screen.getByRole("button", { name: /Version 2/ })).toHaveTextContent(
      "2026",
    );
    expect(
      screen
        .getAllByRole("button", { name: /Version [12]/ })
        .map((button) => button.textContent?.match(/Version \d/)?.[0]),
    ).toEqual(["Version 2", "Version 1"]);
    expect(targets).toEqual([`/api/quizzes/${quizId}/versions?limit=20`]);
    expect(
      screen.getByRole("button", { name: /Version 2/ }),
    ).not.toHaveTextContent("Version 2 question");

    fireEvent.click(screen.getByRole("button", { name: /Version 2/ }));
    const snapshotHeading = await screen.findByRole("heading", {
      name: "Snapshot 2",
    });
    await waitFor(() => expect(snapshotHeading).toHaveFocus());
    expect(targets).toEqual([
      `/api/quizzes/${quizId}/versions?limit=20`,
      `/api/quizzes/${quizId}/versions/2`,
    ]);
    expect(
      screen.getByRole("heading", {
        name: "Published preview · Version 2",
      }),
    ).toBeInTheDocument();
    expect(screen.getByText("Version 2 question")).toBeInTheDocument();
    expect(screen.getByText("Description 2")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "A. Marked correct" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Jump to source/ }),
    ).not.toBeInTheDocument();

    fireEvent.click(closeButton);
    await waitFor(() => expect(trigger).toHaveFocus());
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    fireEvent.click(trigger);
    expect(
      await screen.findByRole("heading", { name: "Snapshot 2" }),
    ).toBeInTheDocument();
    expect(targets).toHaveLength(2);
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it("paginates with the opaque cursor and deduplicates overlapping versions", async () => {
    const targets: string[] = [];
    const { executor } = executorWith(async (request) => {
      const target = String(request.createRequest().target);
      targets.push(target);
      return target.includes("cursor=")
        ? response(200, { items: [summary(1)], nextCursor: null })
        : response(200, {
            items: [summary(2), summary(1)],
            nextCursor: "opaque cursor/+",
          });
    });
    render(<HistoryHarness executor={executor} />);
    fireEvent.click(screen.getByRole("button", { name: "Published versions" }));

    await screen.findByRole("button", { name: /Version 2/ });
    fireEvent.click(screen.getByRole("button", { name: "Load more versions" }));
    await screen.findByText("All published versions loaded.");

    expect(screen.getAllByRole("button", { name: /Version 1/ })).toHaveLength(
      1,
    );
    expect(targets).toEqual([
      `/api/quizzes/${quizId}/versions?limit=20`,
      `/api/quizzes/${quizId}/versions?limit=20&cursor=opaque+cursor%2F%2B`,
    ]);
  });

  it("ignores a stale detail response after the teacher selects another version", async () => {
    let releaseVersionOne: ((value: HttpResponse) => void) | undefined;
    const versionOneResponse = new Promise<HttpResponse>((resolve) => {
      releaseVersionOne = resolve;
    });
    const { executor } = executorWith(async (request) => {
      const target = String(request.createRequest().target);
      if (target.includes("/versions?")) {
        return response(200, {
          items: [summary(2), summary(1)],
          nextCursor: null,
        });
      }
      if (target.endsWith("/1")) {
        return versionOneResponse;
      }
      return response(200, detail(2));
    });
    render(<HistoryHarness executor={executor} />);
    fireEvent.click(screen.getByRole("button", { name: "Published versions" }));

    fireEvent.click(await screen.findByRole("button", { name: /Version 1/ }));
    fireEvent.click(screen.getByRole("button", { name: /Version 2/ }));
    expect(
      await screen.findByRole("heading", { name: "Snapshot 2" }),
    ).toBeInTheDocument();

    releaseVersionOne?.(response(200, detail(1)));
    await waitFor(() =>
      expect(
        screen.getByRole("heading", { name: "Snapshot 2" }),
      ).toBeInTheDocument(),
    );
    expect(
      screen.queryByRole("heading", { name: "Snapshot 1" }),
    ).not.toBeInTheDocument();
  });

  it("keeps loaded history while a failed pagination request is retried", async () => {
    let paginationCalls = 0;
    const { executor } = executorWith(async (request) => {
      const target = String(request.createRequest().target);
      if (!target.includes("cursor=")) {
        return response(200, {
          items: [summary(2)],
          nextCursor: "next-page",
        });
      }
      paginationCalls += 1;
      return paginationCalls === 1
        ? response(500, {
            code: "INTERNAL_ERROR",
            message: "Pagination unavailable",
            status: 500,
            path: target,
          })
        : response(200, { items: [summary(1)], nextCursor: null });
    });
    render(<HistoryHarness executor={executor} />);
    fireEvent.click(screen.getByRole("button", { name: "Published versions" }));
    await screen.findByRole("button", { name: /Version 2/ });

    fireEvent.click(screen.getByRole("button", { name: "Load more versions" }));
    expect(
      await screen.findByText("Pagination unavailable"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Version 2/ }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Load more versions" }));

    expect(
      await screen.findByRole("button", { name: /Version 1/ }),
    ).toBeInTheDocument();
    expect(paginationCalls).toBe(2);
  });

  it("resets list and selection state when the quiz changes", async () => {
    const nextQuizId = "9bd4c564-3c27-4e6d-91aa-a004334aa8f7";
    const { executor } = executorWith(async (request) => {
      const target = String(request.createRequest().target);
      if (target.includes(nextQuizId)) {
        return response(200, { items: [], nextCursor: null });
      }
      if (target.includes("/versions?")) {
        return response(200, { items: [summary(1)], nextCursor: null });
      }
      return response(200, detail(1));
    });
    const { rerender } = render(<HistoryHarness executor={executor} />);
    fireEvent.click(screen.getByRole("button", { name: "Published versions" }));
    fireEvent.click(await screen.findByRole("button", { name: /Version 1/ }));
    await screen.findByRole("heading", { name: "Snapshot 1" });

    rerender(<HistoryHarness executor={executor} quizIdValue={nextQuizId} />);

    expect(
      await screen.findByRole("heading", { name: "No published versions yet" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "Snapshot 1" }),
    ).not.toBeInTheDocument();
  });

  it("shows history and detail failures with working retries", async () => {
    let historyCalls = 0;
    let detailCalls = 0;
    const { executor } = executorWith(async (request) => {
      const target = String(request.createRequest().target);
      if (target.includes("/versions?")) {
        historyCalls += 1;
        return historyCalls === 1
          ? response(500, {
              code: "INTERNAL_ERROR",
              message: "History unavailable",
              status: 500,
              path: target,
            })
          : response(200, { items: [summary(1)], nextCursor: null });
      }
      detailCalls += 1;
      return detailCalls === 1
        ? response(404, {
            code: "QUIZ_VERSION_NOT_FOUND",
            message: "Version not found",
            status: 404,
            path: target,
          })
        : response(200, detail(1));
    });
    render(<HistoryHarness executor={executor} />);
    fireEvent.click(screen.getByRole("button", { name: "Published versions" }));

    expect(await screen.findByText("History unavailable")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry history" }));
    fireEvent.click(await screen.findByRole("button", { name: /Version 1/ }));
    expect(await screen.findByText("Version not found")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry version" }));
    expect(
      await screen.findByRole("heading", { name: "Snapshot 1" }),
    ).toBeInTheDocument();
    expect({ detailCalls, historyCalls }).toEqual({
      detailCalls: 2,
      historyCalls: 2,
    });
  });

  it("renders the explicit empty state without requesting version detail", async () => {
    const { execute, executor } = executorWith(async () =>
      response(200, { items: [], nextCursor: null }),
    );
    render(<HistoryHarness executor={executor} />);
    fireEvent.click(screen.getByRole("button", { name: "Published versions" }));

    expect(
      await screen.findByRole("heading", { name: "No published versions yet" }),
    ).toBeInTheDocument();
    expect(execute).toHaveBeenCalledTimes(1);
  });
});
