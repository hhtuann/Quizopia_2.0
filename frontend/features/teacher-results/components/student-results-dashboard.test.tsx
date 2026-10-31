import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { TeacherDashboardApiClient } from "../api/teacher-dashboard-api-client";
import type { StudentDirectoryQuery } from "../api/teacher-dashboard-api-client";
import type { AssessmentResult } from "../../assessments/api/assessment-foundation";
import type {
  TeacherStudentOverview,
  TeacherStudentSummary,
  TeacherStudents,
} from "../model/teacher-dashboard";
import { StudentResultsDashboardSection } from "./student-results-dashboard";

const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const studentAId = "b7b14962-3a2e-4d2d-926a-3b36ea90c196";
const studentBId = "c7b14962-3a2e-4d2d-926a-3b36ea90c195";
const attemptA1 = "a7b14962-3a2e-4d2d-926a-3b36ea90c197";
const attemptA2 = "d7b14962-3a2e-4d2d-926a-3b36ea90c191";
const attemptB1 = "f7b14962-3a2e-4d2d-926a-3b36ea90c194";

const resultA1 = {
  resultId: "17b14962-3a2e-4d2d-926a-3b36ea90c193",
  attemptId: attemptA1,
  publicationId,
  ordinal: "1",
  earnedWeight: "1",
  possibleWeight: "1",
  percentageDisplay: "100.00",
  gradingPolicyVersion: 1 as const,
  gradingPolicyId: "QUIZOPIA_GRADING_V1" as const,
  terminalReason: "MANUAL_SUBMIT" as const,
  finalizedAt: "2026-10-30T09:20:00Z",
};
const resultB1 = {
  ...resultA1,
  resultId: "27b14962-3a2e-4d2d-926a-3b36ea90c192",
  attemptId: attemptB1,
  ordinal: "1",
  earnedWeight: "1",
  possibleWeight: "20",
  percentageDisplay: "5.00",
  terminalReason: "BOUNDED_TIMEOUT" as const,
};

const studentA: TeacherStudentSummary = {
  studentId: studentAId,
  studentName: null,
  attemptCount: "2",
  gradedAttemptCount: "1",
  hasActive: false,
  hasSubmitted: true,
  latestAttempt: {
    attemptId: attemptA2,
    ordinal: "2",
    state: "ABANDONED",
    startedAt: "2026-10-30T10:00:00Z",
    deadlineAt: null,
    finalizedAt: "2026-10-30T10:05:00Z",
    terminalReason: "STUDENT_ABANDON",
    result: null,
    teacherReviewAvailable: false,
  },
  latestGradedResult: resultA1,
  bestResult: resultA1,
  averageResult: {
    sumEarnedWeight: "1",
    sumPossibleWeight: "1",
    gradedAttemptCount: "1",
    scoreDisplay: "10.00",
  },
};
const studentB: TeacherStudentSummary = {
  studentId: studentBId,
  studentName: "Nguyễn Văn An",
  attemptCount: "1",
  gradedAttemptCount: "1",
  hasActive: false,
  hasSubmitted: true,
  latestAttempt: {
    attemptId: attemptB1,
    ordinal: "1",
    state: "SUBMITTED",
    startedAt: "2026-10-30T08:00:00Z",
    deadlineAt: null,
    finalizedAt: "2026-10-30T08:30:00Z",
    terminalReason: "BOUNDED_TIMEOUT",
    result: resultB1,
    teacherReviewAvailable: true,
  },
  latestGradedResult: resultB1,
  bestResult: resultB1,
  averageResult: {
    sumEarnedWeight: "1",
    sumPossibleWeight: "20",
    gradedAttemptCount: "1",
    scoreDisplay: "0.50",
  },
};

function emptyBins(): TeacherStudentOverview["scoreBins"] {
  return Array.from({ length: 20 }, (_, index) => ({
    index,
    lowerBound: (index / 2).toString(),
    upperBound: ((index + 1) / 2).toString(),
    lowerInclusive: index === 0,
    upperInclusive: true as const,
    count: "0",
  }));
}

function overviewFixture(): TeacherStudentOverview {
  return {
    publicationId,
    asOf: "2026-10-30T11:00:00Z",
    activeStudentCount: "1",
    submittedStudentCount: "2",
    participatedStudentCount: "3",
    gradedStudentCount: "2",
    bestScore: {
      earnedWeight: "1",
      possibleWeight: "1",
      scoreDisplay: "10.00",
    },
    averageBestScore: {
      sumEarnedWeight: "21",
      sumPossibleWeight: "40",
      gradedAttemptCount: "2",
      scoreDisplay: "5.25",
    },
    scoreBins: emptyBins().map((bin) =>
      bin.index === 0 || bin.index === 19 ? { ...bin, count: "1" } : bin,
    ),
  };
}

function api(): TeacherDashboardApiClient {
  return {
    studentOverview: vi.fn(),
    students: vi.fn(),
    studentHistory: vi.fn(),
  };
}

function ok<T>(value: T) {
  return { ok: true as const, status: 200, value };
}
function denied(status: number, code: string) {
  return { ok: false as const, error: { kind: "api" as const, status, code } };
}

function clientWithDirectory(
  items: TeacherStudentSummary[],
  cursor: string | null = null,
) {
  const client = api();
  vi.mocked(client.studentOverview).mockResolvedValue(ok(overviewFixture()));
  vi.mocked(client.students).mockResolvedValue(
    ok({
      publicationId,
      asOf: "2026-10-30T11:00:00Z",
      items,
      nextCursor: cursor,
    }),
  );
  return client;
}

afterEach(() => {
  vi.useRealTimers();
});

describe("StudentResultsDashboardSection", () => {
  it("renders exact distinct KPI counters with the overlap note and never sums groups", async () => {
    const client = clientWithDirectory([studentA, studentB]);
    render(
      <StudentResultsDashboardSection
        client={client}
        publicationId={publicationId}
      />,
    );
    expect(await screen.findByText("Active students")).toBeInTheDocument();
    expect(screen.getByText("Submitted students")).toBeInTheDocument();
    expect(screen.getByText("Total participants")).toBeInTheDocument();
    expect(screen.getByText("Graded students")).toBeInTheDocument();
    // Exact server counters rendered verbatim: 1 active, 2 submitted,
    // 3 participated, 2 graded; never 1+2 as a fabricated total.
    expect(
      screen.getByText("3", { selector: ".font-heading" }),
    ).toBeInTheDocument();
    expect(screen.getAllByText("10.00 / 10").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("5.25 / 10")).toBeInTheDocument();
    expect(
      screen.getByText(/Groups are never added together/i),
    ).toBeInTheDocument();
  });

  it("renders all twenty histogram bins with counts in the accessible table", async () => {
    const client = clientWithDirectory([studentA, studentB]);
    render(
      <StudentResultsDashboardSection
        client={client}
        publicationId={publicationId}
      />,
    );
    expect(
      await screen.findByRole("region", { name: "Score distribution table" }),
    ).toBeInTheDocument();
    const table = screen.getByRole("region", {
      name: "Score distribution table",
    });
    expect(table.querySelectorAll("tbody tr")).toHaveLength(20);
    expect(table).toHaveTextContent("0 – 0.5");
    expect(table).toHaveTextContent("9.5 – 10");
    const populatedRows = Array.from(table.querySelectorAll("tbody tr")).filter(
      (row) => row.lastElementChild?.textContent === "1",
    );
    expect(populatedRows.length).toBe(2);
  });

  it("shows the truthful empty distribution when nothing is graded", async () => {
    const client = api();
    const overview = overviewFixture();
    client.studentOverview = vi.fn().mockResolvedValue(
      ok({
        ...overview,
        activeStudentCount: "0",
        submittedStudentCount: "0",
        participatedStudentCount: "1",
        gradedStudentCount: "0",
        bestScore: null,
        averageBestScore: null,
        scoreBins: emptyBins(),
      }),
    );
    vi.mocked(client.students).mockResolvedValue(
      ok({
        publicationId,
        asOf: "2026-10-30T11:00:00Z",
        items: [],
        nextCursor: null,
      }),
    );
    render(
      <StudentResultsDashboardSection
        client={client}
        publicationId={publicationId}
      />,
    );
    expect(
      await screen.findByText(
        /No graded work yet, so the distribution is empty/i,
      ),
    ).toBeInTheDocument();
    expect(screen.getAllByText(/No graded work yet/i).length).toBeGreaterThan(
      1,
    );
  });

  it("renders one row per student with exact scores; ungraded never renders as zero", async () => {
    const client = clientWithDirectory([studentA, studentB]);
    render(
      <StudentResultsDashboardSection
        client={client}
        publicationId={publicationId}
      />,
    );
    expect(
      (await screen.findAllByText("Nguyễn Văn An")).length,
    ).toBeGreaterThanOrEqual(1);
    expect(
      screen.getAllByTitle("Student ID shown because no name is available")
        .length,
    ).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(studentAId).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("100.00%").length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText("5.00%").length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText("10.00 / 10").length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("0.50 / 10").length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByText("0.00%")).not.toBeInTheDocument();
    // No answers or keys in the directory.
    expect(screen.queryByText(/correct answer/i)).not.toBeInTheDocument();
  });

  it("sends only allowlisted directory query keys and refetches on filter change", async () => {
    const client = clientWithDirectory([studentB]);
    render(
      <StudentResultsDashboardSection
        client={client}
        publicationId={publicationId}
      />,
    );
    await screen.findAllByText("Nguyễn Văn An");
    const first = vi.mocked(client.students).mock.calls[0]?.[1] ?? {};
    const sent = first as Record<string, unknown>;
    expect(Object.keys(sent).filter((key) => sent[key] !== undefined)).toEqual([
      "limit",
      "sort",
      "direction",
    ]);
    fireEvent.change(
      screen.getByLabelText("Filter students by attempt state"),
      {
        target: { value: "SUBMITTED" },
      },
    );
    await waitFor(() =>
      expect(vi.mocked(client.students).mock.calls.length).toBeGreaterThan(1),
    );
    const second = vi.mocked(client.students).mock.calls.at(-1)?.[1];
    expect(second).toMatchObject({
      status: "SUBMITTED",
      sort: "LATEST",
      direction: "DESC",
    });
    fireEvent.click(screen.getByRole("button", { name: "Reverse sort order" }));
    await waitFor(() =>
      expect(vi.mocked(client.students).mock.calls.length).toBeGreaterThan(2),
    );
    expect(vi.mocked(client.students).mock.calls.at(-1)?.[1]).toMatchObject({
      direction: "ASC",
    });
  });

  it("searches only with an exact canonical student ID and reports invalid input", async () => {
    const client = clientWithDirectory([studentB]);
    render(
      <StudentResultsDashboardSection
        client={client}
        publicationId={publicationId}
      />,
    );
    await screen.findAllByText("Nguyễn Văn An");
    const callsBefore = vi.mocked(client.students).mock.calls.length;
    fireEvent.change(screen.getByLabelText(/exact student ID/i), {
      target: { value: "not-a-uuid" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Find" }));
    expect(
      await screen.findByText(/Enter a complete student ID/i),
    ).toBeInTheDocument();
    expect(vi.mocked(client.students).mock.calls.length).toBe(callsBefore);
    fireEvent.change(screen.getByLabelText(/exact student ID/i), {
      target: { value: studentBId },
    });
    fireEvent.click(screen.getByRole("button", { name: "Find" }));
    await waitFor(() =>
      expect(vi.mocked(client.students).mock.calls.length).toBe(
        callsBefore + 1,
      ),
    );
    expect(vi.mocked(client.students).mock.calls.at(-1)?.[1]).toMatchObject({
      studentId: studentBId,
    });
    expect(
      screen.getByText(/Showing the one student with this exact ID/i),
    ).toBeInTheDocument();
  });

  it("paginates with the opaque cursor and deduplicates students across page moves", async () => {
    const client = api();
    vi.mocked(client.studentOverview).mockResolvedValue(ok(overviewFixture()));
    vi.mocked(client.students)
      .mockResolvedValueOnce(
        ok({
          publicationId,
          asOf: "2026-10-30T11:00:00Z",
          items: [studentA],
          nextCursor: "page2.sig",
        }),
      )
      .mockResolvedValue(
        ok({
          publicationId,
          asOf: "2026-10-30T11:00:01Z",
          // studentA moved across the page boundary; the new copy must not duplicate.
          items: [studentA, studentB],
          nextCursor: null,
        }),
      );
    render(
      <StudentResultsDashboardSection
        client={client}
        publicationId={publicationId}
      />,
    );
    const directory = await screen.findByRole("region", {
      name: "Student directory",
    });
    await waitFor(() =>
      expect(directory.querySelectorAll("tbody tr")).toHaveLength(1),
    );
    fireEvent.click(screen.getByRole("button", { name: "Load more students" }));
    // studentA repeats across the page boundary; the table keeps one row per
    // distinct student while the second page adds studentB.
    await waitFor(() =>
      expect(directory.querySelectorAll("tbody tr")).toHaveLength(2),
    );
    expect(vi.mocked(client.students).mock.calls[1]?.[1]).toMatchObject({
      cursor: "page2.sig",
    });
  });

  it("refresh resets pagination to a cursor-less first page", async () => {
    const client = api();
    vi.mocked(client.studentOverview).mockResolvedValue(ok(overviewFixture()));
    vi.mocked(client.students)
      .mockResolvedValueOnce(
        ok({
          publicationId,
          asOf: "2026-10-30T11:00:00Z",
          items: [studentA],
          nextCursor: "page2.sig",
        }),
      )
      .mockResolvedValue(
        ok({
          publicationId,
          asOf: "2026-10-30T11:00:02Z",
          items: [studentA, studentB],
          nextCursor: null,
        }),
      );
    render(
      <StudentResultsDashboardSection
        client={client}
        publicationId={publicationId}
      />,
    );
    await screen.findByRole("button", { name: "Load more students" });
    fireEvent.click(screen.getByRole("button", { name: "Refresh results" }));
    const directory = screen.getByRole("region", {
      name: "Student directory",
    });
    await waitFor(() =>
      expect(directory.querySelectorAll("tbody tr")).toHaveLength(2),
    );
    const last = vi.mocked(client.students).mock.calls.at(-1)?.[1];
    expect(last?.cursor).toBeUndefined();
  });

  it("ignores a stale first-page response that resolves after a newer filter read", async () => {
    const client = api();
    vi.mocked(client.studentOverview).mockResolvedValue(ok(overviewFixture()));
    let releaseStale:
      ((value: AssessmentResult<TeacherStudents>) => void) | null = null;
    vi.mocked(client.students)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            releaseStale = resolve;
          }),
      )
      .mockResolvedValueOnce(
        ok({
          publicationId,
          asOf: "2026-10-30T10:00:00Z",
          items: [studentA],
          nextCursor: null,
        }),
      );
    render(
      <StudentResultsDashboardSection
        client={client}
        publicationId={publicationId}
      />,
    );
    // The initial read stays pending; rows are still shown from the filter read.
    await waitFor(() => expect(releaseStale).not.toBeNull());
    // Controls stay enabled because rows are on screen; a filter change starts
    // a newer sequence-fenced read that supersedes the still-pending one.
    fireEvent.change(
      screen.getByLabelText("Filter students by attempt state"),
      {
        target: { value: "SUBMITTED" },
      },
    );
    await waitFor(() =>
      expect(
        screen.getAllByTitle("Student ID shown because no name is available")
          .length,
      ).toBeGreaterThan(0),
    );
    await act(async () => {
      releaseStale?.(
        ok({
          publicationId,
          asOf: "2026-10-30T11:00:03Z",
          items: [studentB],
          nextCursor: "stale.sig",
        }),
      );
    });
    await waitFor(() => {
      const directory = screen.getByRole("region", {
        name: "Student directory",
      });
      expect(directory.querySelectorAll("tbody tr")).toHaveLength(1);
      expect(screen.queryByText("Nguyễn Văn An")).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "Load more students" }),
      ).not.toBeInTheDocument();
    });
  });

  it("opens the student detail with server-paginated history, badges and authorized links", async () => {
    const client = clientWithDirectory([studentA, studentB]);
    vi.mocked(client.studentHistory)
      .mockResolvedValueOnce(
        ok({
          publicationId,
          studentId: studentAId,
          asOf: "2026-10-30T11:00:00Z",
          items: [studentA.latestAttempt],
          nextCursor: "history2.sig",
        }),
      )
      .mockResolvedValueOnce(
        ok({
          publicationId,
          studentId: studentAId,
          asOf: "2026-10-30T11:00:05Z",
          items: [
            {
              attemptId: attemptA1,
              ordinal: "1",
              state: "SUBMITTED",
              startedAt: "2026-10-30T09:00:00Z",
              deadlineAt: null,
              finalizedAt: "2026-10-30T09:20:00Z",
              terminalReason: "MANUAL_SUBMIT",
              result: resultA1,
              teacherReviewAvailable: true,
            },
          ],
          nextCursor: null,
        }),
      );
    render(
      <StudentResultsDashboardSection
        client={client}
        publicationId={publicationId}
      />,
    );
    const rows = await screen.findAllByRole("button", {
      name: "View attempts",
    });
    fireEvent.click(rows[0]!);
    await screen.findByRole("region", { name: "Attempt history table" });
    // The table and the mobile card list both render in jsdom; both surfaces
    // carry the same authoritative rows.
    expect(
      screen.getAllByText("Student abandoned").length,
    ).toBeGreaterThanOrEqual(1);
    expect(
      screen.getAllByText("No result (ungraded)").length,
    ).toBeGreaterThanOrEqual(1);
    fireEvent.click(screen.getByRole("button", { name: "Load more attempts" }));
    await screen.findAllByRole("link", { name: "Review submitted work" });
    expect(screen.getAllByText("Best attempt").length).toBeGreaterThanOrEqual(
      1,
    );
    expect(screen.getAllByText("Latest attempt").length).toBeGreaterThanOrEqual(
      1,
    );
    expect(screen.getAllByText("100.00%").length).toBeGreaterThanOrEqual(1);
    expect(
      screen.getAllByRole("link", { name: /activity evidence/i }).length,
    ).toBe(4);
    expect(
      screen
        .getAllByRole("link", {
          name: "Open activity evidence for Attempt 1",
        })
        .every(
          (link) =>
            link.getAttribute("href") ===
            `/app/monitoring/teacher/attempts/${attemptA1}`,
        ),
    ).toBe(true);
    // Every review link targets the one graded SUBMITTED attempt; the
    // abandoned latest attempt never offers review.
    const reviewLinks = screen.getAllByRole("link", {
      name: "Review submitted work",
    });
    expect(reviewLinks.length).toBe(2);
    expect(
      reviewLinks.every(
        (link) =>
          link.getAttribute("href") ===
          `/app/assessments/${publicationId}/attempts/${attemptA1}/review`,
      ),
    ).toBe(true);
  });

  it("reports masked owner denial without rendering another teacher's students", async () => {
    const client = api();
    vi.mocked(client.studentOverview).mockResolvedValue(
      denied(403, "ACCESS_DENIED"),
    );
    vi.mocked(client.students).mockResolvedValue(denied(403, "ACCESS_DENIED"));
    render(
      <StudentResultsDashboardSection
        client={client}
        publicationId={publicationId}
      />,
    );
    expect(
      await screen.findByText("Summary could not be read"),
    ).toBeInTheDocument();
    expect(screen.getByText("Students could not be read")).toBeInTheDocument();
    expect(screen.getByText("No students yet")).toBeInTheDocument();
  });

  it("refetches overview and directory through the registered realtime invalidate callback", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const client = clientWithDirectory([studentA]);
    let invalidate: (() => void) | null = null;
    render(
      <StudentResultsDashboardSection
        client={client}
        onRegisterInvalidate={(fn) => {
          invalidate = fn;
        }}
        publicationId={publicationId}
      />,
    );
    await screen.findAllByRole("button", { name: "View attempts" });
    const calls = vi.mocked(client.students).mock.calls.length;
    const overviewCalls = vi.mocked(client.studentOverview).mock.calls.length;
    act(() => {
      invalidate?.();
    });
    act(() => {
      vi.advanceTimersByTime(700);
    });
    await waitFor(() =>
      expect(vi.mocked(client.students).mock.calls.length).toBeGreaterThan(
        calls,
      ),
    );
    expect(vi.mocked(client.studentOverview).mock.calls.length).toBeGreaterThan(
      overviewCalls,
    );
  });
});
