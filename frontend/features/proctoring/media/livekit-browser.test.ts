import { beforeEach, describe, expect, it, vi } from "vitest";
import type { MediaGrant } from "../model/media";
import {
  liveKitBrowserAdapter,
  type BrowserCameraTrack,
} from "./livekit-browser";

const sdk = vi.hoisted(() => ({
  rooms: [] as Array<{
    options: unknown;
    handlers: Map<string, (...args: never[]) => void>;
    connect: ReturnType<typeof vi.fn>;
    disconnect: ReturnType<typeof vi.fn>;
    publishTrack: ReturnType<typeof vi.fn>;
    state: string;
  }>,
}));

vi.mock("livekit-client", () => {
  class Room {
    options: unknown;
    handlers = new Map<string, (...args: never[]) => void>();
    connect = vi.fn(async () => undefined);
    disconnect = vi.fn(async () => undefined);
    state = "connected";
    localParticipant = {
      publishTrack: vi.fn(async () => ({ source: "camera", isMuted: false })),
    };
    constructor(options: unknown) {
      this.options = options;
      sdk.rooms.push({
        options,
        handlers: this.handlers,
        connect: this.connect,
        disconnect: this.disconnect,
        publishTrack: this.localParticipant.publishTrack,
        state: this.state,
      });
    }
    on(event: string, handler: (...args: never[]) => void) {
      this.handlers.set(event, handler);
      return this;
    }
  }
  return {
    Room,
    RoomEvent: {
      Reconnecting: "reconnecting",
      Reconnected: "reconnected",
      Disconnected: "disconnected",
      TrackSubscribed: "trackSubscribed",
      TrackUnsubscribed: "trackUnsubscribed",
    },
    Track: { Source: { Camera: "camera" }, Kind: { Video: "video" } },
    ConnectionState: { Connected: "connected" },
    createLocalVideoTrack: vi.fn(),
  };
});

const grant: MediaGrant = {
  id: "11111111-1111-4111-8111-111111111111",
  url: "wss://livekit.invalid",
  token: "scoped-memory-token",
  leaseUntil: "2026-10-16T12:00:00Z",
  role: "STUDENT",
  technicalState: "GRANT_ISSUED",
};

describe("official LiveKit browser adapter role isolation", () => {
  beforeEach(() => {
    sdk.rooms.splice(0);
  });

  it("publishes only a camera track and disables Student subscriptions", async () => {
    const sdkTrack = {};
    const browserTrack = {
      sdkTrack,
      ended: new Promise<void>(() => undefined),
      attach: vi.fn(),
      detach: vi.fn(),
      stop: vi.fn(),
    } as BrowserCameraTrack & { sdkTrack: unknown };
    const connection = liveKitBrowserAdapter.createStudentConnection(vi.fn());
    await connection.connect(grant, browserTrack);
    const room = sdk.rooms[0]!;
    expect(room.connect).toHaveBeenCalledWith(grant.url, grant.token, {
      autoSubscribe: false,
    });
    expect(room.publishTrack).toHaveBeenCalledWith(sdkTrack, {
      source: "camera",
      simulcast: true,
    });
    await connection.disconnect();
    expect(room.disconnect).toHaveBeenCalledWith(false);
  });

  it("Teacher subscribes without local publication and rejects non-camera tracks", async () => {
    const cameraChanged = vi.fn();
    const connection = liveKitBrowserAdapter.createTeacherConnection(
      vi.fn(),
      cameraChanged,
    );
    const video = document.createElement("video");
    await connection.connect({ ...grant, role: "TEACHER" }, video);
    const room = sdk.rooms[0]!;
    expect(room.connect).toHaveBeenCalledWith(grant.url, grant.token, {
      autoSubscribe: true,
    });
    expect(room.publishTrack).not.toHaveBeenCalled();
    const publication = { source: "microphone", setSubscribed: vi.fn() };
    const subscribed = room.handlers.get("trackSubscribed") as unknown as
      | ((track: { kind: string }, value: typeof publication) => void)
      | undefined;
    subscribed?.({ kind: "audio" }, publication);
    expect(publication.setSubscribed).toHaveBeenCalledWith(false);
  });
});
