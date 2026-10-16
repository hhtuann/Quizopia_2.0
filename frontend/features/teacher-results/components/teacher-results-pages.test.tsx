import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type {
  Result,
  TeacherResultRow,
} from "../../assessments/api/publication-contract.generated";
import type { TeacherResultsApiClient } from "../api/teacher-results-api-client";
import { TeacherResultsScreen } from "./teacher-results-pages";

const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const studentId = "b7b14962-3a2e-4d2d-926a-3b36ea90c196";
const submittedAttemptId = "a7b14962-3a2e-4d2d-926a-3b36ea90c197";
const activeAttemptId = "c7b14962-3a2e-4d2d-926a-3b36ea90c190";
const abandonedAttemptId = "d7b14962-3a2e-4d2d-926a-3b36ea90c191";

const exactResult: Result = {
  resultId: "e7b14962-3a2e-4d2d-926a-3b36ea90c193",
  attemptId: submittedAttemptId,
  publicationId,
  ordinal: "9007199254740993",
  earnedWeight: "12345678901234567890.125",
  possibleWeight: "12345678901234567891",
  percentageDisplay: "100.00",
  gradingPolicyVersion: 1,
  gradingPolicyId: "QUIZOPIA_GRADING_V1",
  terminalReason: "MANUAL_SUBMIT",
  finalizedAt: "2026-10-16T09:30:00Z",
};

const submitted: TeacherResultRow = {
  attemptId: submittedAttemptId,
  studentId,
  ordinal: exactResult.ordinal,
  state: "SUBMITTED",
  result: exactResult,
};
const active: TeacherResultRow = {
  attemptId: activeAttemptId,
  studentId,
  ordinal: "2",
  state: "ACTIVE",
  result: null,
};
const abandoned: TeacherResultRow = {
  attemptId: abandonedAttemptId,
  studentId,
  ordinal: "3",
  state: "ABANDONED",
  result: null,
};

function client(): TeacherResultsApiClient {
  return { results: vi.fn() };
}

describe("Teacher Publication results", () => {
  it("renders ACTIVE, SUBMITTED and ABANDONED with exact nullable totals", async () => {
    const api = client();
    vi.mocked(api.results).mockResolvedValue({
      ok: true,
      status: 200,
      value: { items: [active, submitted, abandoned], nextCursor: null },
    });
    render(<TeacherResultsScreen client={api} publicationId={publicationId} />);

    expect(
      await screen.findByText("No result (still active)"),
    ).toBeInTheDocument();
    expect(screen.getByText("No result (ungraded)")).toBeInTheDocument();
    expect(screen.getByText("100.00%")).toBeInTheDocument();
    expect(
      screen.getByText("12345678901234567890.125 / 12345678901234567891"),
    ).toBeInTheDocument();
    expect(screen.getByText(submittedAttemptId)).toBeInTheDocument();
    expect(
      screen.getByRole("link", {
        name: `Open monitoring for Attempt ${activeAttemptId}`,
      }),
    ).toHaveAttribute(
      "href",
      `/app/monitoring/teacher/attempts/${activeAttemptId}`,
    );
    expect(
      screen.getByRole("link", {
        name: `Open monitoring for Attempt ${submittedAttemptId}`,
      }),
    ).toHaveAttribute(
      "href",
      `/app/monitoring/teacher/attempts/${submittedAttemptId}`,
    );
    expect(screen.queryByText(/correct answer/i)).not.toBeInTheDocument();
    const region = screen.getByRole("region", {
      name: "Scrollable Publication attempt results",
    });
    expect(region).toHaveAttribute("tabindex", "0");
  });

  it("paginates with the exact opaque cursor and keeps real Attempt IDs", async () => {
    const api = client();
    vi.mocked(api.results)
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        value: { items: [active], nextCursor: "opaque.results_2" },
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        value: { items: [submitted], nextCursor: null },
      });
    render(<TeacherResultsScreen client={api} publicationId={publicationId} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Load more rows" }),
    );
    expect(await screen.findByText(submittedAttemptId)).toBeInTheDocument();
    expect(api.results).toHaveBeenNthCalledWith(
      2,
      publicationId,
      { cursor: "opaque.results_2" },
      expect.any(AbortSignal),
    );
  });

  it("shows masked owner denial without rendering another Teacher's rows", async () => {
    const api = client();
    vi.mocked(api.results).mockResolvedValue({
      ok: false,
      error: { kind: "api", status: 404, code: "PUBLICATION_NOT_FOUND" },
    });
    render(<TeacherResultsScreen client={api} publicationId={publicationId} />);
    expect(
      await screen.findByText(
        /unavailable or not accessible for the current account/,
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(studentId)).not.toBeInTheDocument();
  });

  it("reports dependency 503 without presenting it as logout", async () => {
    const api = client();
    vi.mocked(api.results).mockResolvedValue({
      ok: false,
      error: { kind: "api", status: 503, code: "DEPENDENCY_UNAVAILABLE" },
    });
    render(<TeacherResultsScreen client={api} publicationId={publicationId} />);
    expect(
      await screen.findByText(/Your account has not been logged out/),
    ).toBeInTheDocument();
  });
});
