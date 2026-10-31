import { StrictMode } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { ReviewContent } from "./teacher-attempt-review";
import type { SensitiveReadResult } from "../../attempts/audit/observation-client";
import type {
  AuditTimeline,
  TeacherAttemptReview,
} from "../api/teacher-assessment-contract.generated";
const id = "00000000-0000-4000-8000-000000000001";

const review: TeacherAttemptReview = {
  publicationId: id,
  attemptId: id,
  snapshotId: id,
  state: "SUBMITTED",
  revision: "1",
  gradingPolicyVersion: 1,
  startedAt: "2026-10-31T00:00:00Z",
  finalizedAt: "2026-10-31T00:01:00Z",
  result: {
    resultId: id,
    attemptId: id,
    publicationId: id,
    ordinal: "1",
    earnedWeight: "1",
    possibleWeight: "1",
    percentageDisplay: "100.00",
    gradingPolicyVersion: 1,
    gradingPolicyId: "QUIZOPIA_GRADING_V1",
    terminalReason: "MANUAL_SUBMIT",
    finalizedAt: "2026-10-31T00:01:00Z",
  },
  questions: [
    {
      question: {
        number: 1,
        type: "SINGLE_CHOICE",
        stemMarkdown: "Fictional question",
        options: [{ label: "A", markdown: "Alpha" }],
      },
      answer: { type: "SINGLE_CHOICE", optionLabel: "A" },
      correctAnswer: { type: "SINGLE_CHOICE", optionLabel: "A" },
      earnedWeight: "1",
      possibleWeight: "1",
      explanationMarkdown: null,
    },
  ],
};

const timeline: AuditTimeline = {
  items: [
    {
      eventId: id,
      ordinal: "1",
      source: "SERVER_AUTHORITY",
      type: "ANSWER_PERSISTED",
      questionNumber: 1,
      observedAt: null,
      occurredAt: review.startedAt,
      assessmentRevision: "1",
      operationKind: "SAVE",
      terminalReason: null,
      resultId: null,
      noticeId: null,
      receivedAt: review.startedAt,
      transitionDetailStatus: "NOT_APPLICABLE",
      transition: null,
    },
    {
      eventId: "00000000-0000-4000-8000-000000000002",
      ordinal: "2",
      source: "CLIENT_OBSERVATION",
      type: "ANSWER_CHANGED",
      questionNumber: 2,
      observedAt: review.startedAt,
      occurredAt: null,
      assessmentRevision: null,
      operationKind: null,
      terminalReason: null,
      resultId: null,
      noticeId: null,
      receivedAt: review.startedAt,
      transitionDetailStatus: "AVAILABLE",
      transition: { fromOptionLabels: ["A"], toOptionLabels: ["C"] },
    },
  ],
  nextAfter: null,
  coverage: { status: "PARTIAL", reasons: ["CLIENT_GAP"] },
};

function ok<T>(value: T): SensitiveReadResult<T> {
  return { ok: true, value };
}
function denied(
  status: number,
  code: string | null,
): SensitiveReadResult<never> {
  return { ok: false, status, code };
}

it("uses a fresh lifetime after StrictMode effect replay, reads only deliberately, and clears content on denial", async () => {
  const read = vi.fn(async (signal: AbortSignal) => {
    expect(signal.aborted).toBe(false);
    return ok(review);
  });
  const client = {
    review: read,
    timeline: vi.fn().mockResolvedValue(ok(timeline)),
    choice: vi.fn(),
    send: vi.fn(),
  };
  render(
    <StrictMode>
      <ReviewContent client={client} publicationId={id} />
    </StrictMode>,
  );
  expect(read).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText("Read submitted work"));
  await screen.findByText("Submitted answer");
  expect(read).toHaveBeenCalledTimes(1);
  read.mockResolvedValueOnce(denied(403, "ACCESS_DENIED"));
  fireEvent.click(screen.getByText("Read submitted work"));
  await screen.findByText("Answer review unavailable");
  expect(screen.queryByText("Fictional question")).not.toBeInTheDocument();
  read.mockResolvedValueOnce(ok(review));
  fireEvent.click(screen.getByText("Read submitted work"));
  await screen.findByText("Submitted answer");
  fireEvent.click(screen.getByText("Read interaction timeline"));
  await screen.findByText("Question 1", { exact: true });
  await screen.findByText("Question 2", { exact: true });
  expect(screen.getByText(/A → C/)).toBeInTheDocument();
});

it("distinguishes provenance and time sources in the timeline", async () => {
  const client = {
    review: vi.fn().mockResolvedValue(ok(review)),
    timeline: vi.fn().mockResolvedValue(ok(timeline)),
    choice: vi.fn(),
    send: vi.fn(),
  };
  render(<ReviewContent client={client} publicationId={id} />);
  fireEvent.click(screen.getByText("Read submitted work"));
  await screen.findByText("Submitted answer");
  fireEvent.click(screen.getByText("Read interaction timeline"));
  await screen.findByRole("heading", { name: "Interaction timeline" });
  expect(screen.getByText("Server record")).toBeInTheDocument();
  expect(screen.getByText("Browser report")).toBeInTheDocument();
  expect(screen.getByText("Answer saved")).toBeInTheDocument();
  expect(screen.getByText("Answer changed")).toBeInTheDocument();
  expect(screen.getAllByText(/Browser reported:/).length).toBeGreaterThan(0);
  expect(screen.getAllByText(/Server received:/).length).toBeGreaterThan(0);
  expect(
    screen.getByText(/Some browser reports are missing/),
  ).toBeInTheDocument();
});

it("renders the sensitive-policy state truthfully instead of a generic denial", async () => {
  const client = {
    review: vi.fn().mockResolvedValue(denied(503, "SENSITIVE_POLICY_PENDING")),
    timeline: vi.fn(),
    choice: vi.fn(),
    send: vi.fn(),
  };
  render(<ReviewContent client={client} publicationId={id} />);
  fireEvent.click(screen.getByText("Read submitted work"));
  await screen.findByText(/Sensitive review is not available/i);
  expect(screen.queryByText("Submitted answer")).not.toBeInTheDocument();
});
