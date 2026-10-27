import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { MonitoringEvidence } from "../model/monitoring";
import { TeacherTimeline } from "./teacher-timeline";

const base = {
  id: "e7b14962-3a2e-4d2d-926a-3b36ea90c199",
  streamId: "f7b14962-3a2e-4d2d-926a-3b36ea90c199",
  sequence: 1,
  observedAt: "2026-10-16T10:00:00Z",
  questionNumber: 2,
  receivedAt: "2026-10-16T10:00:01Z",
  integritySha256: "a".repeat(64),
  schemaVersion: 1 as const,
  actorId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
};

describe("Teacher timeline provenance", () => {
  it("labels browser observations separately from saved assessment facts", () => {
    const items: MonitoringEvidence[] = [
      {
        ...base,
        ordinal: 1,
        type: "ANSWER_CHANGED",
        source: "CLIENT_OBSERVATION",
        assessmentRevision: null,
      },
      {
        ...base,
        id: "9bd4c564-3c27-4e6d-91aa-a004334aa8f7",
        streamId: null,
        sequence: null,
        ordinal: 2,
        type: "ANSWER_PERSISTED",
        source: "ASSESSMENT_AUTHORITY",
        assessmentRevision: "42",
      },
    ];
    render(<TeacherTimeline items={items} />);
    expect(screen.getByText(/^Browser observation ·/)).toBeInTheDocument();
    expect(
      screen.getByText(/^Saved assessment fact/),
    ).toBeInTheDocument();
    expect(screen.getByText(/Assessment revision 42/)).toBeInTheDocument();
    expect(screen.getAllByText(/neither proves misconduct/i)).toHaveLength(2);
    expect(screen.getAllByText(/Observed:/)).toHaveLength(2);
    expect(screen.getAllByText(/Received:/)).toHaveLength(2);
    expect(document.body).not.toHaveTextContent("answer content");
  });
});
