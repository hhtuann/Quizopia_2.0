import {
  buildAuthFrame,
  buildSubscribeFrame,
  buildUnsubscribeFrame,
  newCanonicalRequestId,
  parseServerFrame,
  realtimeResourceKey,
  type RealtimeResource,
  type ServerFrame,
} from "../model/realtime-frame";
import {
  REALTIME_SUBPROTOCOL,
  browserWebSocketTransport,
  type RealtimeTransport,
  type RealtimeTransportConnection,
} from "./realtime-socket";

export type RealtimeConnectionState =
  | "idle"
  | "connecting"
  | "authenticating"
  | "authenticated"
  | "reconnect-wait"
  | "stopped";

export type RealtimeStopReason =
  | "stopped-by-consumer"
  | "session-expired"
  | "protocol-violation"
  | "credentials-unavailable"
  | "reconnects-exhausted"
  | "misconfigured"
  | null;

export type RealtimeSignalEvent =
  | { readonly type: "invalidated"; readonly resource: RealtimeResource }
  | { readonly type: "resync"; readonly resource: RealtimeResource }
  | {
      readonly type: "revoked";
      readonly resource: RealtimeResource;
      readonly code:
        "NOT_ACCESSIBLE" | "AUTHORITY_UNAVAILABLE" | "TOKEN_EXPIRED";
    }
  /** Hook-level: a connection was (re)established or visibility/online recovered. */
  | { readonly type: "connected" };

export type RealtimeClientEvent =
  | {
      readonly type: "state";
      readonly state: RealtimeConnectionState;
      readonly attempts: number;
      readonly stopReason: RealtimeStopReason;
    }
  | RealtimeSignalEvent;

export interface RealtimeCredentials {
  readonly ok: true;
  readonly ticket: string;
  readonly accessToken: string;
}

export type RealtimeCredentialsResult =
  | RealtimeCredentials
  | { readonly ok: false; readonly error: "session" | "retryable" };

export type RealtimeCredentialProvider =
  () => Promise<RealtimeCredentialsResult>;

export type RealtimeSchedule = (
  callback: () => void,
  milliseconds: number,
) => () => void;

export const defaultRealtimeSchedule: RealtimeSchedule = (
  callback,
  milliseconds,
) => {
  const timer = setTimeout(callback, milliseconds);
  return () => clearTimeout(timer);
};

interface PendingOperation {
  readonly kind: "subscribe" | "unsubscribe";
  readonly resourceKey: string;
  readonly subscriptionId?: string;
}

type FrameDisposition = "handled" | "violation" | "auth-failure";

export interface RealtimeClientOptions {
  readonly credentialsProvider: RealtimeCredentialProvider;
  readonly url: string | null;
  readonly onEvent: (event: RealtimeClientEvent) => void;
  readonly transport?: RealtimeTransport;
  readonly maximumSubscriptions?: number;
  readonly authTimeoutMs?: number;
  readonly reconnectBaseDelayMs?: number;
  readonly reconnectMaximumDelayMs?: number;
  readonly maximumReconnectAttempts?: number;
  readonly jitter?: () => number;
  readonly schedule?: RealtimeSchedule;
}

/**
 * Strict browser realtime client: one AUTH frame carrying the one-time ticket and the
 * current USER token, then bounded subscriptions. Credentials never appear in URLs,
 * storage, logs or emitted events; every reconnect re-fetches a fresh ticket and the
 * current token. Server frames are validated against the frozen schema and matched
 * strictly to this connection's own subscriptions before any consumer signal fires.
 */
export class RealtimeClient {
  private readonly credentialsProvider: RealtimeCredentialProvider;
  private readonly url: string | null;
  private readonly onEvent: (event: RealtimeClientEvent) => void;
  private readonly transport: RealtimeTransport;
  private readonly maximumSubscriptions: number;
  private readonly authTimeoutMs: number;
  private readonly reconnectBaseDelayMs: number;
  private readonly reconnectMaximumDelayMs: number;
  private readonly maximumReconnectAttempts: number;
  private readonly jitter: () => number;
  private readonly schedule: RealtimeSchedule;

  private state: RealtimeConnectionState = "idle";
  private stopReason: RealtimeStopReason = null;
  private attempts = 0;
  private loop = 0;
  private stopped = true;
  private resolveDelay: (() => void) | null = null;

  private connection: RealtimeTransportConnection | null = null;
  private connectionAuthenticated = false;
  private connectionClose: ((code: number) => void) | null = null;

  private readonly desired = new Map<string, RealtimeResource>();
  private readonly active = new Map<string, string>();
  private readonly bySubscriptionId = new Map<string, string>();
  private readonly pending = new Map<string, PendingOperation>();

  constructor(options: RealtimeClientOptions) {
    this.credentialsProvider = options.credentialsProvider;
    this.url = options.url;
    this.onEvent = options.onEvent;
    this.transport = options.transport ?? browserWebSocketTransport;
    this.maximumSubscriptions = options.maximumSubscriptions ?? 8;
    this.authTimeoutMs = options.authTimeoutMs ?? 6_000;
    this.reconnectBaseDelayMs = options.reconnectBaseDelayMs ?? 1_000;
    this.reconnectMaximumDelayMs = options.reconnectMaximumDelayMs ?? 30_000;
    this.maximumReconnectAttempts = options.maximumReconnectAttempts ?? 8;
    this.jitter = options.jitter ?? Math.random;
    this.schedule = options.schedule ?? defaultRealtimeSchedule;
  }

  get currentState(): RealtimeConnectionState {
    return this.state;
  }

  start(): void {
    if (!this.stopped) return;
    if (this.url === null) {
      this.transition("stopped", "misconfigured");
      return;
    }
    this.stopped = false;
    this.stopReason = null;
    this.attempts = 0;
    const loop = ++this.loop;
    void this.runLoop(loop);
  }

  stop(): void {
    if (this.stopped && this.state === "stopped") return;
    this.shutdown("stopped-by-consumer");
  }

  /** Visibility/online recovery: skip any remaining wait and retry with fresh credentials. */
  restart(): void {
    if (this.stopped) return;
    this.attempts = 0;
    this.resolveDelay?.();
  }

  setResources(resources: readonly RealtimeResource[]): void {
    const next = new Map<string, RealtimeResource>();
    for (const resource of resources)
      next.set(realtimeResourceKey(resource), resource);
    for (const key of [...this.desired.keys()]) {
      if (next.has(key)) continue;
      this.desired.delete(key);
      const subscriptionId = this.active.get(key);
      if (subscriptionId !== undefined)
        this.requestUnsubscribe(key, subscriptionId);
    }
    for (const [key, resource] of next) {
      if (!this.desired.has(key)) this.desired.set(key, resource);
    }
    this.flushSubscriptions();
  }

  private shutdown(reason: RealtimeStopReason): void {
    this.loop++;
    this.stopped = true;
    this.resolveDelay?.();
    this.resolveDelay = null;
    this.detachConnection(1000);
    this.desired.clear();
    this.transition("stopped", reason);
  }

  private transition(
    state: RealtimeConnectionState,
    stopReason: RealtimeStopReason = null,
  ): void {
    this.state = state;
    this.stopReason = stopReason;
    this.onEvent({ type: "state", state, attempts: this.attempts, stopReason });
  }

  private backoffDelayMs(): number {
    const exponential = Math.min(
      this.reconnectMaximumDelayMs,
      this.reconnectBaseDelayMs * 2 ** Math.max(0, this.attempts - 1),
    );
    return Math.round(exponential * (0.7 + 0.6 * this.jitter()));
  }

  private async runLoop(loop: number): Promise<void> {
    while (!this.stopped && loop === this.loop) {
      this.transition("connecting");
      const credentials = await this.credentialsProvider();
      if (this.stopped || loop !== this.loop) return;
      if (!credentials.ok) {
        if (credentials.error === "session") {
          this.shutdown("credentials-unavailable");
          return;
        }
        this.attempts++;
        if (this.attempts > this.maximumReconnectAttempts) {
          this.shutdown("reconnects-exhausted");
          return;
        }
        await this.waitBackoff();
        continue;
      }
      const outcome = await this.runConnection(loop, credentials);
      if (this.stopped || loop !== this.loop) return;
      this.detachConnection(outcome.code);
      if (outcome.code === 4401 || outcome.code === 4400) {
        this.shutdown(
          outcome.code === 4401 ? "session-expired" : "protocol-violation",
        );
        return;
      }
      this.attempts++;
      if (this.attempts > this.maximumReconnectAttempts) {
        this.shutdown("reconnects-exhausted");
        return;
      }
      await this.waitBackoff();
    }
  }

  private waitBackoff(): Promise<void> {
    this.transition("reconnect-wait");
    return new Promise((resolve) => {
      let finished = false;
      const cancel = this.schedule(() => complete(), this.backoffDelayMs());
      const complete = () => {
        if (finished) return;
        finished = true;
        cancel();
        if (this.resolveDelay === complete) this.resolveDelay = null;
        resolve();
      };
      this.resolveDelay = complete;
    });
  }

  private runConnection(
    loop: number,
    credentials: RealtimeCredentials,
  ): Promise<{ readonly code: number }> {
    return new Promise((resolve) => {
      let settled = false;
      let cancelAuthTimeout: (() => void) | null = null;
      const finish = (code: number) => {
        if (settled) return;
        settled = true;
        cancelAuthTimeout?.();
        this.connectionClose = null;
        this.connectionAuthenticated = false;
        if (this.connection !== null) {
          const connection = this.connection;
          this.connection = null;
          connection.close();
        }
        resolve({ code });
      };
      this.connectionAuthenticated = false;
      this.connection = this.transport.open({
        url: this.url as string,
        subprotocol: REALTIME_SUBPROTOCOL,
        handlers: {
          onOpen: () => {
            if (settled || loop !== this.loop) return;
            this.transition("authenticating");
            // The one-time ticket and current token travel only inside this first frame.
            this.connection?.send(
              buildAuthFrame(credentials.ticket, credentials.accessToken),
            );
            cancelAuthTimeout = this.schedule(
              () => finish(1006),
              this.authTimeoutMs,
            );
          },
          onMessage: (text) => {
            if (settled || loop !== this.loop) return;
            const frame = parseServerFrame(text);
            if (frame === null) {
              finish(4400);
              return;
            }
            const disposition = this.handleFrame(frame);
            if (disposition === "handled") return;
            finish(disposition === "auth-failure" ? 4401 : 4400);
          },
          onClose: (code) => finish(code),
          onError: () => {
            /* A close event follows; nothing else to do on its own. */
          },
        },
      });
      this.connectionClose = finish;
      if (this.stopped || loop !== this.loop) finish(1000);
    });
  }

  private handleFrame(frame: ServerFrame): FrameDisposition {
    if (!this.connectionAuthenticated) {
      if (frame.type === "WELCOME") return "handled";
      if (frame.type === "AUTHENTICATED") {
        this.connectionAuthenticated = true;
        this.transition("authenticated");
        this.flushSubscriptions();
        return "handled";
      }
      return "violation";
    }
    switch (frame.type) {
      case "WELCOME":
      case "AUTHENTICATED":
        return "violation";
      case "SUBSCRIBED": {
        const operation = this.pending.get(frame.requestId);
        if (operation === undefined || operation.kind !== "subscribe")
          return "violation";
        this.pending.delete(frame.requestId);
        if (realtimeResourceKey(frame.resource) !== operation.resourceKey)
          return "violation";
        const previous = this.active.get(operation.resourceKey);
        if (previous !== undefined) this.bySubscriptionId.delete(previous);
        this.active.set(operation.resourceKey, frame.subscriptionId);
        this.bySubscriptionId.set(frame.subscriptionId, operation.resourceKey);
        if (!this.desired.has(operation.resourceKey))
          this.requestUnsubscribe(operation.resourceKey, frame.subscriptionId);
        return "handled";
      }
      case "UNSUBSCRIBED": {
        const operation = this.pending.get(frame.requestId);
        if (operation === undefined || operation.kind !== "unsubscribe")
          return "violation";
        this.pending.delete(frame.requestId);
        this.dropSubscription(operation.resourceKey, frame.subscriptionId);
        return "handled";
      }
      case "RESOURCE_INVALIDATED": {
        const resourceKey = this.bySubscriptionId.get(frame.subscriptionId);
        if (resourceKey === undefined) return "handled";
        if (resourceKey !== realtimeResourceKey(frame.resource))
          return "handled";
        const resource = this.desired.get(resourceKey);
        if (resource === undefined) return "handled";
        this.onEvent({ type: "invalidated", resource });
        return "handled";
      }
      case "RESYNC_REQUIRED": {
        const resourceKey = this.bySubscriptionId.get(frame.subscriptionId);
        if (resourceKey === undefined) return "handled";
        const resource = this.desired.get(resourceKey);
        if (resource === undefined) return "handled";
        this.onEvent({ type: "resync", resource });
        return "handled";
      }
      case "SUBSCRIPTION_REVOKED": {
        const resourceKey = this.bySubscriptionId.get(frame.subscriptionId);
        if (resourceKey === undefined) return "handled";
        const resource = this.desired.get(resourceKey);
        if (resource === undefined) return "handled";
        this.dropSubscription(resourceKey, frame.subscriptionId);
        this.desired.delete(resourceKey);
        this.onEvent({ type: "revoked", resource, code: frame.code });
        return "handled";
      }
      case "ERROR": {
        if (frame.requestId !== undefined) {
          this.pending.delete(frame.requestId);
          return "handled";
        }
        return frame.code === "AUTH_FAILED" || frame.code === "AUTH_REQUIRED"
          ? "auth-failure"
          : "handled";
      }
    }
  }

  private dropSubscription(resourceKey: string, subscriptionId: string): void {
    if (this.active.get(resourceKey) === subscriptionId)
      this.active.delete(resourceKey);
    this.bySubscriptionId.delete(subscriptionId);
  }

  private requestUnsubscribe(
    resourceKey: string,
    subscriptionId: string,
  ): void {
    if (this.connection === null || !this.connectionAuthenticated) {
      this.dropSubscription(resourceKey, subscriptionId);
      return;
    }
    const requestId = newCanonicalRequestId();
    this.pending.set(requestId, {
      kind: "unsubscribe",
      resourceKey,
      subscriptionId,
    });
    this.connection.send(buildUnsubscribeFrame(requestId, subscriptionId));
  }

  private flushSubscriptions(): void {
    if (this.connection === null || !this.connectionAuthenticated) return;
    for (const [resourceKey, resource] of this.desired) {
      if (this.active.has(resourceKey)) continue;
      if (
        [...this.pending.values()].some(
          (operation) => operation.resourceKey === resourceKey,
        )
      )
        continue;
      if (this.active.size + this.pending.size >= this.maximumSubscriptions)
        return;
      const requestId = newCanonicalRequestId();
      this.pending.set(requestId, { kind: "subscribe", resourceKey });
      this.connection.send(buildSubscribeFrame(requestId, resource));
    }
  }

  private detachConnection(code: number): void {
    if (this.connectionClose !== null) {
      const close = this.connectionClose;
      this.connectionClose = null;
      close(code);
    }
    if (this.connection !== null) {
      const connection = this.connection;
      this.connection = null;
      connection.close();
    }
    this.connectionAuthenticated = false;
    this.active.clear();
    this.bySubscriptionId.clear();
    this.pending.clear();
  }
}
