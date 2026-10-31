import { StrictMode } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { ReviewContent } from "./teacher-attempt-review";
import type { TeacherAttemptReview } from "../api/teacher-assessment-contract.generated";
const id = "00000000-0000-4000-8000-000000000001";
it("uses a fresh lifetime after StrictMode effect replay, reads only deliberately, and clears content on denial", async () => {
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
  const read = vi.fn(async (signal: AbortSignal) => {
    expect(signal.aborted).toBe(false);
    return review as TeacherAttemptReview | null;
  });
  const timeline = {
    items: [
      {
        eventId: id,
        ordinal: "1",
        source: "SERVER_AUTHORITY" as const,
        type: "ANSWER_PERSISTED" as const,
        questionNumber: 1,
        observedAt: null,
        occurredAt: review.startedAt,
        assessmentRevision: "1",
        operationKind: "SAVE" as const,
        terminalReason: null,
        resultId: null,
        noticeId: null,
        receivedAt: review.startedAt,
        transitionDetailStatus: "NOT_APPLICABLE" as const,
        transition: null,
      },
      {
        eventId: "00000000-0000-4000-8000-000000000002",
        ordinal: "2",
        source: "CLIENT_OBSERVATION" as const,
        type: "ANSWER_CHANGED" as const,
        questionNumber: 2,
        observedAt: review.startedAt,
        occurredAt: null,
        assessmentRevision: null,
        operationKind: null,
        terminalReason: null,
        resultId: null,
        noticeId: null,
        receivedAt: review.startedAt,
        transitionDetailStatus: "AVAILABLE" as const,
        transition: { fromOptionLabels: ["A"], toOptionLabels: ["C"] },
      },
    ],
    nextAfter: null,
    coverage: { status: "PARTIAL" as const, reasons: ["CLIENT_GAP" as const] },
  };
  const client = {
    review: read,
    timeline: vi.fn().mockResolvedValue(timeline),
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
  read.mockResolvedValueOnce(null);
  fireEvent.click(screen.getByText("Read submitted work"));
  await screen.findByText("Answer review unavailable");
  expect(screen.queryByText("Fictional question")).not.toBeInTheDocument();
  read.mockResolvedValueOnce(review);
  fireEvent.click(screen.getByText("Read submitted work"));
  await screen.findByText("Submitted answer");
  fireEvent.click(screen.getByText("Read interaction timeline"));
  await screen.findByText("Question 1", { exact: true });
  await screen.findByText("Question 2", { exact: true });
  expect(screen.getByText(/A → C/)).toBeInTheDocument();
});
