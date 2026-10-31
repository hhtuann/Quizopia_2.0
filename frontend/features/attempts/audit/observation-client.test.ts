import { expect, it, vi } from "vitest";
import { observationClient } from "./observation-client";
import type { AuthenticatedRequestExecutor } from "../../../lib/api/authenticated-request";

const id = "00000000-0000-4000-8000-000000000001";

function executor(result: unknown): AuthenticatedRequestExecutor {
  return {
    executeOnce: vi.fn().mockResolvedValue(result),
    execute: vi.fn(),
    executeOnceWithAccessToken: vi.fn(),
  } as unknown as AuthenticatedRequestExecutor;
}

it("preserves a received denial status even if its body cannot be decoded", async () => {
  const once = vi.fn().mockResolvedValue({
    kind: "transport-error",
    error: { kind: "invalid-response", status: 403 },
  });
  const client = observationClient(
    {
      executeOnce: once,
      execute: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    } as unknown as AuthenticatedRequestExecutor,
    () => 1,
    id,
    id,
  );
  expect(
    await client.send(
      {
        schemaVersion: 1,
        eventId: id,
        streamId: id,
        sequence: 1,
        type: "QUESTION_VIEWED",
        questionNumber: 1,
      },
      new AbortController().signal,
    ),
  ).toBe(403);
  expect(once).toHaveBeenCalledTimes(1);
});

it("never issues an optional request from a stale session generation", async () => {
  let generation = 1;
  const once = vi.fn();
  const client = observationClient(
    {
      executeOnce: once,
      execute: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    } as unknown as AuthenticatedRequestExecutor,
    () => generation,
    id,
    id,
  );
  generation = 2;
  expect(
    await client.choice(undefined, new AbortController().signal),
  ).toBeNull();
  expect(
    await client.send(
      {
        schemaVersion: 1,
        eventId: id,
        streamId: id,
        sequence: 1,
        type: "QUESTION_VIEWED",
        questionNumber: 1,
      },
      new AbortController().signal,
    ),
  ).toBe(401);
  expect(once).not.toHaveBeenCalled();
});

it("maps session and refresh failures to denial, not retry", async () => {
  const client = observationClient(
    executor({ kind: "authentication-failure" }),
    () => 1,
    id,
    id,
  );
  expect(
    await client.choice(undefined, new AbortController().signal),
  ).toBeNull();
});
