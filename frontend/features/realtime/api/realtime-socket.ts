/**
 * Injectable realtime transport so tests script sockets without touching globals. The
 * browser adapter talks to the same-origin public edge only, with the exact frozen
 * subprotocol and never any query string or credential in the URL.
 */

export interface RealtimeTransportConnection {
  send(data: string): void;
  close(): void;
}

export interface RealtimeTransportHandlers {
  onOpen(): void;
  onMessage(text: string): void;
  onClose(code: number, reason: string): void;
  onError(): void;
}

export interface RealtimeTransport {
  open(input: {
    readonly url: string;
    readonly subprotocol: string;
    readonly handlers: RealtimeTransportHandlers;
  }): RealtimeTransportConnection;
}

export const REALTIME_SUBPROTOCOL = "quizopia.realtime.v1";
export const REALTIME_WEBSOCKET_PATH = "/ws/realtime";

/** Derived strictly from the public API base; no credentials, no query, no fragments. */
export function realtimeWebSocketUrl(apiBaseUrl?: string): string | null {
  const base = apiBaseUrl ?? process.env.NEXT_PUBLIC_API_URL;
  if (base) {
    const trimmed = base.replace(/\/+$/, "");
    const websocketBase = trimmed
      .replace(/^http:\/\//i, "ws://")
      .replace(/^https:\/\//i, "wss://");
    if (!/^wss?:\/\//i.test(websocketBase)) return null;
    return `${websocketBase}${REALTIME_WEBSOCKET_PATH}`;
  }
  if (typeof window === "undefined") return null;
  const scheme = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${scheme}//${window.location.host}${REALTIME_WEBSOCKET_PATH}`;
}

export const browserWebSocketTransport: RealtimeTransport = {
  open({ url, subprotocol, handlers }) {
    const socket = new WebSocket(url, [subprotocol]);
    socket.addEventListener("open", () => handlers.onOpen());
    socket.addEventListener("message", (event) => {
      if (typeof event.data === "string") handlers.onMessage(event.data);
      else handlers.onError();
    });
    socket.addEventListener("close", (event) =>
      handlers.onClose(event.code, event.reason ?? ""),
    );
    socket.addEventListener("error", () => handlers.onError());
    return {
      send(data) {
        if (socket.readyState === WebSocket.OPEN) socket.send(data);
      },
      close() {
        if (
          socket.readyState === WebSocket.OPEN ||
          socket.readyState === WebSocket.CONNECTING
        )
          socket.close(1000, "client-stop");
      },
    };
  },
};
