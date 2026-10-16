import type { MediaGrant } from "../model/media";

export type MediaConnectionSignal =
  "connected" | "reconnecting" | "reconnected" | "disconnected";

export interface BrowserCameraTrack {
  readonly ended: Promise<void>;
  attach(element: HTMLVideoElement): void;
  detach(): void;
  stop(): void;
}

export interface StudentMediaConnection {
  connect(grant: MediaGrant, track: BrowserCameraTrack): Promise<void>;
  disconnect(): Promise<void>;
}

export interface TeacherMediaConnection {
  connect(grant: MediaGrant, video: HTMLVideoElement): Promise<void>;
  disconnect(): Promise<void>;
  hasCamera(): boolean;
}

export interface LiveKitBrowserAdapter {
  createCameraTrack(): Promise<BrowserCameraTrack>;
  createStudentConnection(
    onSignal: (signal: MediaConnectionSignal) => void,
  ): StudentMediaConnection;
  createTeacherConnection(
    onSignal: (signal: MediaConnectionSignal) => void,
    onCamera: (available: boolean) => void,
  ): TeacherMediaConnection;
}

export type CameraPermissionFailure =
  "permission-denied" | "device-missing" | "device-error";

export function classifyCameraFailure(error: unknown): CameraPermissionFailure {
  if (error instanceof DOMException) {
    if (error.name === "NotAllowedError" || error.name === "SecurityError")
      return "permission-denied";
    if (error.name === "NotFoundError" || error.name === "DevicesNotFoundError")
      return "device-missing";
  }
  return "device-error";
}

/**
 * Lazy-loads the SDK only after the authorized user deliberately starts media.
 * The scoped token remains a local call argument and is never cached by React.
 */
export const liveKitBrowserAdapter: LiveKitBrowserAdapter = {
  async createCameraTrack() {
    const { createLocalVideoTrack } = await import("livekit-client");
    const track = await createLocalVideoTrack();
    let resolveEnded: () => void = () => undefined;
    const ended = new Promise<void>((resolve) => {
      resolveEnded = resolve;
    });
    track.mediaStreamTrack.addEventListener("ended", resolveEnded, {
      once: true,
    });
    return {
      ended,
      attach(element) {
        track.attach(element);
      },
      detach() {
        track.detach();
      },
      stop() {
        track.stop();
      },
      /** Internal SDK track, intentionally inaccessible to feature consumers. */
      sdkTrack: track,
    } as BrowserCameraTrack & { readonly sdkTrack: typeof track };
  },
  createStudentConnection(onSignal) {
    let room: import("livekit-client").Room | null = null;
    return {
      async connect(grant, browserTrack) {
        const { ConnectionState, Room, RoomEvent, Track } =
          await import("livekit-client");
        const sdkTrack = (
          browserTrack as BrowserCameraTrack & {
            readonly sdkTrack: import("livekit-client").LocalVideoTrack;
          }
        ).sdkTrack;
        const nextRoom = new Room({ adaptiveStream: true, dynacast: true });
        room = nextRoom;
        nextRoom.on(RoomEvent.Reconnecting, () => onSignal("reconnecting"));
        nextRoom.on(RoomEvent.Reconnected, () => onSignal("reconnected"));
        nextRoom.on(RoomEvent.Disconnected, () => onSignal("disconnected"));
        await nextRoom.connect(grant.url, grant.token, {
          autoSubscribe: false,
        });
        const publication = await nextRoom.localParticipant.publishTrack(
          sdkTrack,
          {
            source: Track.Source.Camera,
            simulcast: true,
          },
        );
        if (
          nextRoom.state !== ConnectionState.Connected ||
          publication.source !== Track.Source.Camera ||
          publication.isMuted
        ) {
          throw new Error("Camera publication was not established.");
        }
        onSignal("connected");
      },
      async disconnect() {
        const current = room;
        room = null;
        // Track ownership stays with useStudentCamera. Keeping it alive here
        // permits the explicit fresh-grant reconnect path; terminal cleanup
        // separately stops the track before disconnecting the room.
        if (current !== null) await current.disconnect(false);
      },
    };
  },
  createTeacherConnection(onSignal, onCamera) {
    let room: import("livekit-client").Room | null = null;
    let attachedTrack: import("livekit-client").RemoteTrack | null = null;
    return {
      async connect(grant, video) {
        const { ConnectionState, Room, RoomEvent, Track } =
          await import("livekit-client");
        const nextRoom = new Room({ adaptiveStream: true });
        room = nextRoom;
        nextRoom.on(RoomEvent.Reconnecting, () => onSignal("reconnecting"));
        nextRoom.on(RoomEvent.Reconnected, () => onSignal("reconnected"));
        nextRoom.on(RoomEvent.Disconnected, () => {
          attachedTrack?.detach(video);
          attachedTrack = null;
          onCamera(false);
          onSignal("disconnected");
        });
        nextRoom.on(RoomEvent.TrackSubscribed, (track, publication) => {
          if (
            track.kind !== Track.Kind.Video ||
            publication.source !== Track.Source.Camera
          ) {
            publication.setSubscribed(false);
            return;
          }
          attachedTrack?.detach(video);
          attachedTrack = track;
          track.attach(video);
          onCamera(true);
        });
        nextRoom.on(RoomEvent.TrackUnsubscribed, (track) => {
          track.detach(video);
          if (track === attachedTrack) {
            attachedTrack = null;
            onCamera(false);
          }
        });
        await nextRoom.connect(grant.url, grant.token, {
          autoSubscribe: true,
        });
        if (nextRoom.state !== ConnectionState.Connected)
          throw new Error("Teacher subscription was not established.");
        onSignal("connected");
      },
      async disconnect() {
        const current = room;
        room = null;
        attachedTrack?.detach();
        attachedTrack = null;
        onCamera(false);
        if (current !== null) await current.disconnect(true);
      },
      hasCamera: () => attachedTrack !== null,
    };
  },
};
