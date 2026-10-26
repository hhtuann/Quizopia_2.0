import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { MonitoringEvidence } from "../model/monitoring";
import { TeacherTimeline } from "./teacher-timeline";
import { I18nProvider } from "../../../lib/i18n/provider";

const base = {
  id: "e7b14962-3a2e-4d2d-926a-3b36ea90c199",
  streamId: "f7b14962-3a2e-4d2d-926a-3b36ea90c198",
  sequence: 1,
  observedAt: "2026-10-16T10:00:00Z",
  questionNumber: 2,
  receivedAt: "2026-10-16T10:00:01Z",
  integritySha256: "a".repeat(64),
  schemaVersion: 1 as const,
  actorId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
};

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

describe("Teacher monitoring bilingual timeline labels", () => {
  it("localizes evidence copy and provenance in Vietnamese", () => {
    render(
      <I18nProvider initialLocale="vi">
        <TeacherTimeline items={items} />
      </I18nProvider>,
    );
    expect(screen.getByText("Đổi đáp án (báo cáo trình duyệt)")).toBeVisible();
    expect(screen.getByText(/^Quan sát trình duyệt ·/)).toBeVisible();
    expect(
      screen.getByText(
        "Dữ liệu Assessment đã lưu · Câu hỏi 2 · Phiên bản Assessment 42",
      ),
    ).toBeVisible();
    expect(screen.getAllByText(/^Đã nhận: /).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/^Quan sát\/xảy ra: /).length).toBeGreaterThan(
      0,
    );
  });

  it("keeps English labels deterministic without a provider", () => {
    render(<TeacherTimeline items={items} />);
    expect(screen.getByText("Answer changed (browser report)")).toBeVisible();
    expect(screen.getByText(/^Browser observation ·/)).toBeVisible();
    expect(
      screen.getByText(
        "Assessment persistence fact · Question 2 · Assessment revision 42",
      ),
    ).toBeVisible();
  });

  it("localizes the empty timeline state", () => {
    render(
      <I18nProvider initialLocale="vi">
        <TeacherTimeline items={[]} />
      </I18nProvider>,
    );
    expect(
      screen.getByText(
        "Chưa có dữ liệu giám sát nào cho phiên được cấp quyền này.",
      ),
    ).toBeVisible();
  });
});
