import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ActivityEvidenceStream } from "../activity/activity-evidence-stream";
import type { MediaApiClient } from "../api/media-api-client";
import type {
  BrowserCameraTrack,
  LiveKitBrowserAdapter,
  MediaConnectionSignal,
} from "../media/livekit-browser";
import type { MediaGrant } from "../model/media";
import { useStudentCamera } from "./use-student-camera";
import { useTeacherCamera } from "./use-teacher-camera";

const mocks = vi.hoisted(() => ({ clients: null as unknown }));
vi.mock("./use-proctoring-clients", () => ({
  useProctoringClients: () => mocks.clients,
}));

afterEach(() => vi.useRealTimers());

const session = {
  id: "e7b14962-3a2e-4d2d-926a-3b36ea90c199",
  attemptId: "f7b14962-3a2e-4d2d-926a-3b36ea90c199",
  publicationId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  classroomId: "9bd4c564-3c27-4e6d-91aa-a004334aa8f7",
  studentId: "a7a4c564-3c27-4e6d-91aa-a004334aa8f9",
  deadlineAt: "2099-10-16T12:00:00Z",
  status: "ACTIVE" as const,
  createdAt: "2026-10-16T10:00:00Z",
  endedAt: null,
};

function mediaGrant(
  role: "STUDENT" | "TEACHER",
  leaseUntil = "2099-10-16T12:00:00Z",
): MediaGrant {
  return {
    id: "b7a4c564-3c27-4e6d-91aa-a004334aa8f9",
    url: "wss://livekit.example",
    token: "memory-only-scoped-token",
    leaseUntil,
    role,
    technicalState: "GRANT_ISSUED",
  };
}

function installClients(
  role: "STUDENT" | "TEACHER",
  leaseUntil = "2099-10-16T12:00:00Z",
) {
  const media: MediaApiClient = {
    createGrant: vi.fn(async () => ({
      ok: true as const,
      value: mediaGrant(role, leaseUntil),
    })),
    readGrant: vi.fn(),
    renewGrant: vi.fn(async () => ({
      ok: true as const,
      value: mediaGrant(role, leaseUntil),
    })),
    revokeGrant: vi.fn(async () => ({
      ok: true as const,
      value: {
        id: mediaGrant(role).id,
        leaseUntil: mediaGrant(role).leaseUntil,
        role,
        status: "REVOKE_PENDING" as const,
      },
    })),
  };
  mocks.clients = {
    media,
    monitoring: {},
    generation: 1,
    getSessionGeneration: () => 1,
  };
  return media;
}

function studentAdapter(options?: {
  permissionDenied?: boolean;
  disconnectGate?: Promise<void>;
}) {
  let onSignal: ((signal: MediaConnectionSignal) => void) | null = null;
  let endTrack: () => void = () => undefined;
  const track: BrowserCameraTrack = {
    ended: new Promise<void>((resolve) => {
      endTrack = resolve;
    }),
    attach: vi.fn(),
    detach: vi.fn(),
    stop: vi.fn(),
  };
  const adapter: LiveKitBrowserAdapter = {
    createCameraTrack: vi.fn(async () => {
      if (options?.permissionDenied)
        throw new DOMException("denied", "NotAllowedError");
      return track;
    }),
    createStudentConnection: vi.fn((listener) => {
      onSignal = listener;
      return {
        connect: vi.fn(async () => listener("connected")),
        disconnect: vi.fn(async () => {
          await options?.disconnectGate;
          listener("disconnected");
        }),
      };
    }),
    createTeacherConnection: vi.fn(() => {
      throw new Error("student must not create Teacher connection");
    }),
  };
  return {
    adapter,
    track,
    endTrack: () => endTrack(),
    signal: (value: MediaConnectionSignal) => onSignal?.(value),
  };
}

function StudentHarness({
  adapter,
}: {
  readonly adapter: LiveKitBrowserAdapter;
}) {
  const [observe] = useState(() => vi.fn(() => true));
  const activity = { observe } as unknown as ActivityEvidenceStream;
  const camera = useStudentCamera({ session, activity, adapter });
  return (
    <>
      <p>{camera.state}</p>
      <button onClick={() => void camera.start()}>start</button>
      <button onClick={() => void camera.stop()}>stop</button>
      <output>{JSON.stringify(observe.mock.calls)}</output>
      <output data-testid="student-cleanup">
        {JSON.stringify(camera.cleanupReport)}
      </output>
    </>
  );
}

describe("Student media lifecycle", () => {
  beforeEach(() => {
    mocks.clients = null;
  });

  it("does not request permission until deliberate action, publishes through Student-only adapter, and cleans up", async () => {
    const media = installClients("STUDENT");
    const fixture = studentAdapter();
    const view = render(<StudentHarness adapter={fixture.adapter} />);
    expect(fixture.adapter.createCameraTrack).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    await screen.findByText("live");
    expect(fixture.adapter.createCameraTrack).toHaveBeenCalledTimes(1);
    expect(fixture.adapter.createTeacherConnection).not.toHaveBeenCalled();
    expect(screen.getByText(/CAMERA_PERMISSION_GRANTED/)).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("memory-only-scoped-token");
    fireEvent.click(screen.getByRole("button", { name: "stop" }));
    await screen.findByText("disconnected");
    expect(fixture.track.stop).toHaveBeenCalled();
    expect(media.revokeGrant).toHaveBeenCalled();
    expect(screen.getByTestId("student-cleanup")).toHaveTextContent(
      /"sdkDisconnect":"succeeded".*"backendRevoke":"revoke-pending"/,
    );
    view.unmount();
  });

  it("reports permission denial without requesting a media grant", async () => {
    const media = installClients("STUDENT");
    const fixture = studentAdapter({ permissionDenied: true });
    render(<StudentHarness adapter={fixture.adapter} />);
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    await screen.findByText("permission-denied");
    expect(media.createGrant).not.toHaveBeenCalled();
    expect(screen.getByText(/CAMERA_PERMISSION_DENIED/)).toBeInTheDocument();
  });

  it("disconnects on SDK reconnect instead of silently reusing the grant", async () => {
    installClients("STUDENT");
    const fixture = studentAdapter();
    render(<StudentHarness adapter={fixture.adapter} />);
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    await screen.findByText("live");
    act(() => fixture.signal("reconnecting"));
    await screen.findByText("reconnecting");
    expect(screen.getByText(/RECONNECTING/)).toBeInTheDocument();
  });

  it("revokes a successful grant that arrives after unmount", async () => {
    const media = installClients("STUDENT");
    let resolveGrant!: (
      value: Awaited<ReturnType<MediaApiClient["createGrant"]>>,
    ) => void;
    vi.mocked(media.createGrant).mockReturnValue(
      new Promise((resolve) => {
        resolveGrant = resolve;
      }),
    );
    const fixture = studentAdapter();
    const view = render(<StudentHarness adapter={fixture.adapter} />);
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    await screen.findByText("connecting");
    view.unmount();
    resolveGrant({ ok: true, value: mediaGrant("STUDENT") });
    await waitFor(() => expect(media.revokeGrant).toHaveBeenCalled());
    expect(fixture.track.stop).toHaveBeenCalled();
  });

  it("cancels renewal and revokes when the room disconnects", async () => {
    vi.useFakeTimers();
    const media = installClients(
      "STUDENT",
      new Date(Date.now() + 3_000).toISOString(),
    );
    const fixture = studentAdapter();
    render(<StudentHarness adapter={fixture.adapter} />);
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(screen.getByText("live")).toBeInTheDocument();
    act(() => fixture.signal("disconnected"));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    expect(media.revokeGrant).toHaveBeenCalled();
    expect(media.renewGrant).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  it("waits for prior Student teardown and does not let old stop overwrite restart", async () => {
    installClients("STUDENT");
    let finishDisconnect!: () => void;
    const disconnectGate = new Promise<void>((resolve) => {
      finishDisconnect = resolve;
    });
    const fixture = studentAdapter({ disconnectGate });
    render(<StudentHarness adapter={fixture.adapter} />);
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    await screen.findByText("live");
    fireEvent.click(screen.getByRole("button", { name: "stop" }));
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    expect(fixture.adapter.createCameraTrack).toHaveBeenCalledTimes(1);
    finishDisconnect();
    await waitFor(() =>
      expect(fixture.adapter.createCameraTrack).toHaveBeenCalledTimes(2),
    );
    await screen.findByText("live");
  });
});

function TeacherHarness({
  adapter,
}: {
  readonly adapter: LiveKitBrowserAdapter;
}) {
  const { videoRef, state, cleanupReport, connect, stop } = useTeacherCamera({
    session,
    adapter,
  });
  return (
    <>
      <video ref={videoRef} />
      <p>{state}</p>
      <button onClick={() => void connect()}>connect</button>
      <button onClick={() => void stop()}>stop teacher</button>
      <output data-testid="teacher-cleanup">
        {JSON.stringify(cleanupReport)}
      </output>
    </>
  );
}

describe("Teacher media lifecycle", () => {
  it("uses only a subscribe connection and never requests or publishes a local camera", async () => {
    const media = installClients("TEACHER");
    const createCameraTrack = vi.fn();
    const adapter: LiveKitBrowserAdapter = {
      createCameraTrack,
      createStudentConnection: vi.fn(() => {
        throw new Error("Teacher must not publish");
      }),
      createTeacherConnection: vi.fn((onSignal, onCamera) => ({
        async connect() {
          onSignal("connected");
          onCamera(true);
        },
        async disconnect() {
          onCamera(false);
          onSignal("disconnected");
        },
        hasCamera: () => true,
      })),
    };
    const view = render(<TeacherHarness adapter={adapter} />);
    fireEvent.click(screen.getByRole("button", { name: "connect" }));
    await waitFor(() => expect(screen.getByText("live")).toBeInTheDocument());
    expect(createCameraTrack).not.toHaveBeenCalled();
    expect(adapter.createStudentConnection).not.toHaveBeenCalled();
    expect(document.body).not.toHaveTextContent("memory-only-scoped-token");
    view.unmount();
    await waitFor(() => expect(media.revokeGrant).toHaveBeenCalled());
  });

  it("cancels Teacher renewal and revokes on disconnect", async () => {
    vi.useFakeTimers();
    const media = installClients(
      "TEACHER",
      new Date(Date.now() + 3_000).toISOString(),
    );
    let signal: ((value: MediaConnectionSignal) => void) | null = null;
    const adapter: LiveKitBrowserAdapter = {
      createCameraTrack: vi.fn(),
      createStudentConnection: vi.fn(() => {
        throw new Error("no publish");
      }),
      createTeacherConnection: vi.fn((onSignal, onCamera) => {
        signal = onSignal;
        return {
          async connect() {
            onSignal("connected");
            onCamera(true);
          },
          async disconnect() {
            onCamera(false);
          },
          hasCamera: () => true,
        };
      }),
    };
    render(<TeacherHarness adapter={adapter} />);
    fireEvent.click(screen.getByRole("button", { name: "connect" }));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(screen.getByText("live")).toBeInTheDocument();
    act(() => signal?.("disconnected"));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    expect(media.revokeGrant).toHaveBeenCalled();
    expect(media.renewGrant).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  it("serializes Teacher stop then reconnect without stale disconnected state", async () => {
    const media = installClients("TEACHER");
    let finishDisconnect!: () => void;
    const disconnectGate = new Promise<void>((resolve) => {
      finishDisconnect = resolve;
    });
    const adapter: LiveKitBrowserAdapter = {
      createCameraTrack: vi.fn(),
      createStudentConnection: vi.fn(() => {
        throw new Error("no publish");
      }),
      createTeacherConnection: vi.fn((onSignal, onCamera) => ({
        async connect() {
          onSignal("connected");
          onCamera(true);
        },
        async disconnect() {
          await disconnectGate;
          onCamera(false);
        },
        hasCamera: () => true,
      })),
    };
    render(<TeacherHarness adapter={adapter} />);
    fireEvent.click(screen.getByRole("button", { name: "connect" }));
    await screen.findByText("live");
    fireEvent.click(screen.getByRole("button", { name: "stop teacher" }));
    fireEvent.click(screen.getByRole("button", { name: "connect" }));
    expect(media.createGrant).toHaveBeenCalledTimes(1);
    finishDisconnect();
    await waitFor(() => expect(media.createGrant).toHaveBeenCalledTimes(2));
    await screen.findByText("live");
  });
});
