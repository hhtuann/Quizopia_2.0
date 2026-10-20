import { describe, expect, it } from "vitest";
import {
  buildAuthFrame,
  buildSubscribeFrame,
  buildUnsubscribeFrame,
  isCanonicalUuid,
  newCanonicalRequestId,
  parseServerFrame,
} from "./realtime-frame";

const uuid = () => crypto.randomUUID();

describe("parseServerFrame", () => {
  it("accepts each frozen server frame with exact fields", () => {
    expect(
      parseServerFrame(
        `{"protocolVersion":1,"type":"WELCOME","connectionId":"${uuid()}"}`,
      ),
    ).toEqual({
      type: "WELCOME",
      connectionId: expect.any(String),
    });
    expect(
      parseServerFrame('{"protocolVersion":1,"type":"AUTHENTICATED"}'),
    ).toEqual({
      type: "AUTHENTICATED",
    });
    const requestId = uuid();
    const attemptId = uuid();
    const subscribed = parseServerFrame(
      `{"protocolVersion":1,"type":"SUBSCRIBED","requestId":"${requestId}","subscriptionId":"s1","resource":{"kind":"ATTEMPT_OWNER","id":"${attemptId}"}}`,
    );
    expect(subscribed).toEqual({
      type: "SUBSCRIBED",
      requestId,
      subscriptionId: "s1",
      resource: { kind: "ATTEMPT_OWNER", id: attemptId },
    });
    expect(
      parseServerFrame(
        `{"protocolVersion":1,"type":"RESOURCE_INVALIDATED","eventId":"${uuid()}","subscriptionId":"s1","resource":{"kind":"PUBLICATION_TEACHER","id":"${uuid()}"},"reason":"CHANGED","occurredAt":"2026-10-20T10:00:00Z"}`,
      ),
    ).toMatchObject({ type: "RESOURCE_INVALIDATED", reason: "CHANGED" });
    expect(
      parseServerFrame(
        '{"protocolVersion":1,"type":"RESYNC_REQUIRED","subscriptionId":"s1"}',
      ),
    ).toEqual({ type: "RESYNC_REQUIRED", subscriptionId: "s1" });
    expect(
      parseServerFrame(
        '{"protocolVersion":1,"type":"SUBSCRIPTION_REVOKED","subscriptionId":"s1","code":"TOKEN_EXPIRED"}',
      ),
    ).toEqual({
      type: "SUBSCRIPTION_REVOKED",
      subscriptionId: "s1",
      code: "TOKEN_EXPIRED",
    });
    expect(
      parseServerFrame(
        `{"protocolVersion":1,"type":"ERROR","code":"CAPACITY_EXCEEDED","requestId":"${uuid()}"}`,
      ),
    ).toMatchObject({ type: "ERROR", code: "CAPACITY_EXCEEDED" });
    expect(
      parseServerFrame(
        '{"protocolVersion":1,"type":"ERROR","code":"AUTHORITY_UNAVAILABLE"}',
      ),
    ).toEqual({ type: "ERROR", code: "AUTHORITY_UNAVAILABLE" });
  });

  it("rejects every malformed or extended shape", () => {
    const invalid = [
      "",
      "null",
      "[]",
      "{}",
      '{"protocolVersion":"1","type":"AUTHENTICATED"}',
      '{"protocolVersion":1.5,"type":"AUTHENTICATED"}',
      '{"protocolVersion":2,"type":"AUTHENTICATED"}',
      '{"protocolVersion":1,"type":"AUTHENTICATED","extra":true}',
      '{"protocolVersion":1,"type":"WELCOME","connectionId":"not-a-uuid"}',
      '{"protocolVersion":1,"type":"WELCOME","connectionId":"NOT-CANONICAL-UUID"}',
      `{"protocolVersion":1,"type":"UNKNOWN","eventId":"${uuid()}"}`,
      '{"protocolVersion":1,"type":"SUBSCRIBED","requestId":"no","subscriptionId":"s","resource":null}',
      `{"protocolVersion":1,"type":"SUBSCRIBED","requestId":"${uuid()}","subscriptionId":"s","resource":{"kind":"FOREIGN","id":"${uuid()}"}}`,
      `{"protocolVersion":1,"type":"SUBSCRIBED","requestId":"${uuid()}","subscriptionId":"s","resource":{"kind":"ATTEMPT_OWNER","id":"${uuid()}","x":1}}`,
      '{"protocolVersion":1,"type":"RESYNC_REQUIRED","subscriptionId":""}',
      '{"protocolVersion":1,"type":"SUBSCRIPTION_REVOKED","subscriptionId":"s","code":"WEIRD"}',
      '{"protocolVersion":1,"type":"ERROR","code":"NOT_A_CODE"}',
      '{"protocolVersion":1,"type":"ERROR","code":"AUTHORITY_UNAVAILABLE","requestId":"nope"}',
      '{"protocolVersion":1,"type":"RESOURCE_INVALIDATED","eventId":"x","subscriptionId":"s","resource":{"kind":"ATTEMPT_OWNER","id":"' +
        uuid() +
        '"},"reason":"OTHER","occurredAt":"z"}',
    ];
    for (const frame of invalid)
      expect(parseServerFrame(frame), frame).toBeNull();
  });
});

describe("client frame builders", () => {
  it("builds canonical frames with fresh correlation ids", () => {
    const auth = buildAuthFrame(
      "ticket-value-43-chars-aaaaaaaaaaaaaaaaaaaa",
      "token",
    );
    expect(JSON.parse(auth)).toEqual({
      protocolVersion: 1,
      type: "AUTH",
      ticket: "ticket-value-43-chars-aaaaaaaaaaaaaaaaaaaa",
      accessToken: "token",
    });
    const requestId = newCanonicalRequestId();
    expect(isCanonicalUuid(requestId)).toBe(true);
    const subscribe = buildSubscribeFrame(requestId, {
      kind: "PUBLICATION_TEACHER",
      id: uuid(),
    });
    expect(JSON.parse(subscribe)).toEqual({
      protocolVersion: 1,
      type: "SUBSCRIBE",
      requestId,
      resource: { kind: "PUBLICATION_TEACHER", id: expect.any(String) },
    });
    const unsubscribe = buildUnsubscribeFrame(requestId, "sub-1");
    expect(JSON.parse(unsubscribe)).toEqual({
      protocolVersion: 1,
      type: "UNSUBSCRIBE",
      requestId,
      subscriptionId: "sub-1",
    });
  });
});
