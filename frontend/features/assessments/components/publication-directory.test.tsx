import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type {
  PublicationApiClient,
  PublicationResult,
} from "../api/publication-api-client";
import type {
  PublicationSummary,
  PublicationSummaryPage,
} from "../api/publication-contract.generated";
import { PublicationDirectory } from "./publication-directory";

const first: PublicationSummary = {
  publicationId: "e7b14962-3a2e-4d2d-926a-3b36ea90c199",
  title: "Algebra preparation",
  audience: "CLASS",
  state: "DRAFT",
  startAt: "2026-10-26T08:00:00Z",
  endAt: "2026-10-26T09:00:00Z",
  createdAt: "2026-10-25T08:00:00Z",
  revision: 0,
  quizId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  quizVersionId: "9bd4c564-3c27-4e6d-91aa-a004334aa8f7",
  versionNumber: 3,
};
function setup() {
  const list = vi.fn<PublicationApiClient["list"]>();
  const api: PublicationApiClient = {
    list,
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    open: vi.fn(),
    close: vi.fn(),
    extend: vi.fn(),
  };
  return { list, api };
}
const page = (
  items: PublicationSummary[],
  nextCursor: string | null = null,
): PublicationResult<PublicationSummaryPage> => ({
  ok: true,
  value: { items, nextCursor },
});

describe("My Assessments directory", () => {
  it("loads bounded pages, deduplicates, and offers results only for OPEN/CLOSED", async () => {
    const { api, list } = setup();
    const second = {
      ...first,
      publicationId: first.quizId,
      title: "Algebra results",
      state: "OPEN" as const,
    };
    list
      .mockResolvedValueOnce(page([first], "opaque"))
      .mockResolvedValueOnce(page([first, second]));
    render(<PublicationDirectory client={api} />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading assessments");
    await screen.findByRole("link", { name: "Manage Algebra preparation" });
    expect(
      screen.queryByRole("link", { name: /Results for/ }),
    ).not.toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "Load more assessments" }),
    );
    await screen.findByRole("link", { name: "Results for Algebra results" });
    expect(screen.getAllByText("Algebra preparation")).toHaveLength(1);
    expect(list.mock.calls[1]![0]).toEqual({ cursor: "opaque" });
    expect(
      screen.queryByRole("button", { name: "Load more assessments" }),
    ).not.toBeInTheDocument();
  });
  it("cancels obsolete searches and ignores a late response, resetting the cursor on filters", async () => {
    const { api, list } = setup();
    let finish!: (result: PublicationResult<PublicationSummaryPage>) => void;
    list
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      )
      .mockResolvedValue(page([]));
    const mounted = render(<PublicationDirectory client={api} />);
    fireEvent.change(screen.getByLabelText("Search assessments"), {
      target: { value: "  algebra  " },
    });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    await screen.findByText("No matching assessments");
    expect(list.mock.calls[0]![1]!.aborted).toBe(true);
    expect(list.mock.calls[1]![0]).toEqual({
      search: "algebra",
      state: undefined,
    });
    await act(async () => finish(page([first])));
    expect(screen.queryByText(first.title)).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Publication state"), {
      target: { value: "CLOSED" },
    });
    await waitFor(() =>
      expect(list.mock.calls[2]![0]).toEqual({
        search: "algebra",
        state: "CLOSED",
      }),
    );
    mounted.unmount();
    expect(list.mock.calls[2]![1]!.aborted).toBe(true);
  });
  it("recovers from unavailable authority without logging out and clears empty filters", async () => {
    const { api, list } = setup();
    list
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 503, code: "AUTHORITY_UNAVAILABLE" },
      })
      .mockResolvedValue(page([]));
    render(<PublicationDirectory client={api} />);
    expect(
      await screen.findByText(/account has not been logged out/),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry assessments" }));
    await screen.findByText("No assessments yet");
    expect(
      screen.getByRole("link", { name: "Open Quiz Library" }),
    ).toHaveAttribute("href", "/app/quizzes");
    fireEvent.change(screen.getByLabelText("Publication state"), {
      target: { value: "DRAFT" },
    });
    await screen.findByText("No matching assessments");
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    await screen.findByText("No assessments yet");
    expect(screen.getByLabelText("Publication state")).toHaveValue("");
  });
});
