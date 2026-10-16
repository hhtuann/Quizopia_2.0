import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { MonitoringApiClient } from "../api/monitoring-api-client";
import type { MonitoringSession } from "../model/monitoring";
import { useStudentMonitoring } from "./use-student-monitoring";

const mocks = vi.hoisted(() => ({ clients: null as unknown }));
vi.mock("./use-proctoring-clients", () => ({
  useProctoringClients: () => mocks.clients,
}));

const attemptA = "11111111-1111-4111-8111-111111111111";
const attemptB = "22222222-2222-4222-8222-222222222222";

function monitoringSession(attemptId: string): MonitoringSession {
  return {
    id:
      attemptId === attemptA
        ? "33333333-3333-4333-8333-333333333333"
        : "44444444-4444-4444-8444-444444444444",
    attemptId,
    publicationId: "55555555-5555-4555-8555-555555555555",
    classroomId: "66666666-6666-4666-8666-666666666666",
    studentId: "77777777-7777-4777-8777-777777777777",
    deadlineAt: "2099-10-16T12:00:00Z",
    status: "ACTIVE",
    createdAt: "2026-10-16T10:00:00Z",
    endedAt: null,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}

function Harness() {
  const monitoring = useStudentMonitoring();
  return (
    <>
      <output>
        {monitoring.state.status === "active"
          ? monitoring.state.session.attemptId
          : monitoring.state.status}
      </output>
      <button onClick={() => void monitoring.verifyAndStart(attemptA)}>
        start A
      </button>
      <button onClick={() => void monitoring.verifyAndStart(attemptB)}>
        start B
      </button>
    </>
  );
}

describe("Student monitoring lifecycle fencing", () => {
  beforeEach(() => {
    mocks.clients = null;
  });

  it("lets only the newest concurrent start attach an Attempt", async () => {
    const first =
      deferred<Awaited<ReturnType<MonitoringApiClient["createSession"]>>>();
    const second =
      deferred<Awaited<ReturnType<MonitoringApiClient["createSession"]>>>();
    const createSession = vi
      .fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    mocks.clients = {
      generation: 1,
      getSessionGeneration: () => 1,
      monitoring: { createSession, appendEvent: vi.fn(), reconcile: vi.fn() },
      media: {},
    };
    const view = render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: "start A" }));
    await waitFor(() => expect(createSession).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: "start B" }));
    await waitFor(() => expect(createSession).toHaveBeenCalledTimes(2));
    second.resolve({ ok: true, value: monitoringSession(attemptB) });
    await screen.findByText(attemptB);
    first.resolve({ ok: true, value: monitoringSession(attemptA) });
    await waitFor(() => expect(screen.getByText(attemptB)).toBeInTheDocument());
    view.unmount();
  });

  it("does not attach observers when unmounted before session creation resolves", async () => {
    const pending =
      deferred<Awaited<ReturnType<MonitoringApiClient["createSession"]>>>();
    const createSession = vi.fn(() => pending.promise);
    mocks.clients = {
      generation: 1,
      getSessionGeneration: () => 1,
      monitoring: { createSession, appendEvent: vi.fn(), reconcile: vi.fn() },
      media: {},
    };
    const add = vi.spyOn(window, "addEventListener");
    const view = render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: "start A" }));
    await waitFor(() => expect(createSession).toHaveBeenCalled());
    const before = add.mock.calls.length;
    view.unmount();
    pending.resolve({ ok: true, value: monitoringSession(attemptA) });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(add.mock.calls).toHaveLength(before);
    add.mockRestore();
  });

  it("rejects a mismatched server Attempt binding", async () => {
    mocks.clients = {
      generation: 1,
      getSessionGeneration: () => 1,
      monitoring: {
        createSession: vi.fn(async () => ({
          ok: true,
          value: monitoringSession(attemptB),
        })),
        appendEvent: vi.fn(),
        reconcile: vi.fn(),
      },
      media: {},
    };
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: "start A" }));
    await screen.findByText("unavailable");
  });
});
