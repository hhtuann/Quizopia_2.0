/**
 * Frozen realtime-ws-v1 protocol frames (W4-A1 R3). The browser client only ever accepts
 * server frames that match this schema exactly; anything else is a protocol violation.
 */

export type RealtimeResourceKind = "ATTEMPT_OWNER" | "PUBLICATION_TEACHER";

export interface RealtimeResource {
  readonly kind: RealtimeResourceKind;
  readonly id: string;
}

export type RealtimeErrorCode =
  | "INVALID_FRAME"
  | "UNSUPPORTED_VERSION"
  | "AUTH_REQUIRED"
  | "AUTH_FAILED"
  | "NOT_ACCESSIBLE"
  | "AUTHORITY_UNAVAILABLE"
  | "CAPACITY_EXCEEDED";

export type SubscriptionRevokedCode =
  "NOT_ACCESSIBLE" | "AUTHORITY_UNAVAILABLE" | "TOKEN_EXPIRED";

export type ServerFrame =
  | { readonly type: "WELCOME"; readonly connectionId: string }
  | { readonly type: "AUTHENTICATED" }
  | {
      readonly type: "SUBSCRIBED";
      readonly requestId: string;
      readonly subscriptionId: string;
      readonly resource: RealtimeResource;
    }
  | {
      readonly type: "UNSUBSCRIBED";
      readonly requestId: string;
      readonly subscriptionId: string;
    }
  | {
      readonly type: "RESOURCE_INVALIDATED";
      readonly eventId: string;
      readonly subscriptionId: string;
      readonly resource: RealtimeResource;
      readonly reason: "CHANGED";
      readonly occurredAt: string;
    }
  | { readonly type: "RESYNC_REQUIRED"; readonly subscriptionId: string }
  | {
      readonly type: "SUBSCRIPTION_REVOKED";
      readonly subscriptionId: string;
      readonly code: SubscriptionRevokedCode;
    }
  | {
      readonly type: "ERROR";
      readonly code: RealtimeErrorCode;
      readonly requestId?: string;
    };

const CANONICAL_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const RESOURCE_KINDS: readonly string[] = [
  "ATTEMPT_OWNER",
  "PUBLICATION_TEACHER",
];
const ERROR_CODES: readonly string[] = [
  "INVALID_FRAME",
  "UNSUPPORTED_VERSION",
  "AUTH_REQUIRED",
  "AUTH_FAILED",
  "NOT_ACCESSIBLE",
  "AUTHORITY_UNAVAILABLE",
  "CAPACITY_EXCEEDED",
];
const REVOKED_CODES: readonly string[] = [
  "NOT_ACCESSIBLE",
  "AUTHORITY_UNAVAILABLE",
  "TOKEN_EXPIRED",
];

export function isCanonicalUuid(value: unknown): value is string {
  return typeof value === "string" && CANONICAL_UUID.test(value);
}

export function newCanonicalRequestId(): string {
  const generated = crypto.randomUUID();
  return generated.toLowerCase();
}

export function realtimeResourceKey(resource: RealtimeResource): string {
  return `${resource.kind}:${resource.id}`;
}

function isProtocolVersionOne(root: Record<string, unknown>): boolean {
  const version = root.protocolVersion;
  return (
    typeof version === "number" && Number.isInteger(version) && version === 1
  );
}

function resourceOf(value: unknown): RealtimeResource | null {
  if (typeof value !== "object" || value === null) return null;
  const candidate = value as Record<string, unknown>;
  const keys = Object.keys(candidate);
  if (keys.length !== 2 || !("kind" in candidate) || !("id" in candidate))
    return null;
  if (
    typeof candidate.kind !== "string" ||
    !RESOURCE_KINDS.includes(candidate.kind) ||
    !isCanonicalUuid(candidate.id)
  )
    return null;
  return { kind: candidate.kind as RealtimeResourceKind, id: candidate.id };
}

function subscriptionIdOf(value: unknown): string | null {
  return typeof value === "string" && value.length >= 1 && value.length <= 80
    ? value
    : null;
}

/**
 * Strict server frame validation against the frozen schema: exact field sets, integral
 * protocol version, canonical UUIDs and enum values only. Returns null for any violation.
 */
export function parseServerFrame(raw: string): ServerFrame | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null) return null;
  const root = parsed as Record<string, unknown>;
  if (!isProtocolVersionOne(root) || typeof root.type !== "string") return null;

  const fields = Object.keys(root).filter(
    (key) => key !== "protocolVersion" && key !== "type",
  );
  const hasExactFields = (...expected: string[]): boolean => {
    if (fields.length !== expected.length) return false;
    return expected.every((field) => field in root);
  };

  switch (root.type) {
    case "WELCOME":
      return hasExactFields("connectionId") &&
        isCanonicalUuid(root.connectionId)
        ? { type: "WELCOME", connectionId: root.connectionId }
        : null;
    case "AUTHENTICATED":
      return hasExactFields() ? { type: "AUTHENTICATED" } : null;
    case "SUBSCRIBED": {
      if (!hasExactFields("requestId", "subscriptionId", "resource"))
        return null;
      const resource = resourceOf(root.resource);
      const subscriptionId = subscriptionIdOf(root.subscriptionId);
      return isCanonicalUuid(root.requestId) &&
        resource !== null &&
        subscriptionId !== null
        ? {
            type: "SUBSCRIBED",
            requestId: root.requestId,
            subscriptionId,
            resource,
          }
        : null;
    }
    case "UNSUBSCRIBED": {
      if (!hasExactFields("requestId", "subscriptionId")) return null;
      const subscriptionId = subscriptionIdOf(root.subscriptionId);
      return isCanonicalUuid(root.requestId) && subscriptionId !== null
        ? { type: "UNSUBSCRIBED", requestId: root.requestId, subscriptionId }
        : null;
    }
    case "RESOURCE_INVALIDATED": {
      if (
        !hasExactFields(
          "eventId",
          "subscriptionId",
          "resource",
          "reason",
          "occurredAt",
        )
      )
        return null;
      const resource = resourceOf(root.resource);
      const subscriptionId = subscriptionIdOf(root.subscriptionId);
      return isCanonicalUuid(root.eventId) &&
        resource !== null &&
        subscriptionId !== null &&
        root.reason === "CHANGED" &&
        typeof root.occurredAt === "string"
        ? {
            type: "RESOURCE_INVALIDATED",
            eventId: root.eventId,
            subscriptionId,
            resource,
            reason: "CHANGED",
            occurredAt: root.occurredAt,
          }
        : null;
    }
    case "RESYNC_REQUIRED":
      return hasExactFields("subscriptionId") &&
        subscriptionIdOf(root.subscriptionId) !== null
        ? {
            type: "RESYNC_REQUIRED",
            subscriptionId: root.subscriptionId as string,
          }
        : null;
    case "SUBSCRIPTION_REVOKED": {
      if (!hasExactFields("subscriptionId", "code")) return null;
      const subscriptionId = subscriptionIdOf(root.subscriptionId);
      return subscriptionId !== null &&
        typeof root.code === "string" &&
        REVOKED_CODES.includes(root.code)
        ? {
            type: "SUBSCRIPTION_REVOKED",
            subscriptionId,
            code: root.code as SubscriptionRevokedCode,
          }
        : null;
    }
    case "ERROR": {
      if (typeof root.code !== "string" || !ERROR_CODES.includes(root.code))
        return null;
      const hasRequestId = "requestId" in root;
      if (fields.length !== (hasRequestId ? 2 : 1)) return null;
      if (hasRequestId && !isCanonicalUuid(root.requestId)) return null;
      return {
        type: "ERROR",
        code: root.code as RealtimeErrorCode,
        ...(hasRequestId ? { requestId: root.requestId as string } : {}),
      };
    }
    default:
      return null;
  }
}

/** The first and only pre-authentication client frame; secrets never travel elsewhere. */
export function buildAuthFrame(ticket: string, accessToken: string): string {
  return JSON.stringify({
    protocolVersion: 1,
    type: "AUTH",
    ticket,
    accessToken,
  });
}

export function buildSubscribeFrame(
  requestId: string,
  resource: RealtimeResource,
): string {
  return JSON.stringify({
    protocolVersion: 1,
    type: "SUBSCRIBE",
    requestId,
    resource: { kind: resource.kind, id: resource.id },
  });
}

export function buildUnsubscribeFrame(
  requestId: string,
  subscriptionId: string,
): string {
  return JSON.stringify({
    protocolVersion: 1,
    type: "UNSUBSCRIBE",
    requestId,
    subscriptionId,
  });
}
