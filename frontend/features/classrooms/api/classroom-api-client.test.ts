import { describe, expect, it, vi } from "vitest";
import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import { createClassroomApiClient } from "./classroom-api-client";

const userId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const id = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const metadata = {
  id,
  name: "Networks",
  subject: "Computing",
  grade: "12",
  description: null,
};
function response(value: unknown, status = 200): AuthenticatedRequestResult {
  return {
    kind: "response",
    response: {
      ok: status === 200,
      status,
      headers: new Headers({ "cache-control": "no-store" }),
      body: { kind: "json", value },
    },
  };
}
function harness() {
  let generation: number | null = 1;
  const execute = vi.fn<AuthenticatedRequestExecutor["execute"]>();
  const client = createClassroomApiClient({
    authenticatedRequests: {
      execute,
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(),
    },
    getSessionGeneration: () => generation,
    userId,
    apiBaseUrl: "http://localhost:8080",
  });
  return {
    client,
    execute,
    changeSession: () => {
      generation = 2;
    },
  };
}
describe("Classroom authenticated Gateway client", () => {
  it("uses bounded owner collection and preserves opaque continuation", async () => {
    const { client, execute } = harness();
    execute.mockResolvedValue(
      response({
        items: [{ ...metadata, createdAt: "2026-10-26T08:00:00Z" }],
        nextCursor: null,
      }),
    );
    expect((await client.list("opaque+/=")).ok).toBe(true);
    const request = execute.mock.calls[0]![0].createRequest();
    const url = new URL(String(request.target));
    expect(url.origin + url.pathname).toBe("http://localhost:8080/classrooms");
    expect(Object.fromEntries(url.searchParams)).toEqual({
      limit: "20",
      cursor: "opaque+/=",
    });
    expect(request.method).toBe("GET");
    expect(request.body).toBeUndefined();
  });
  it("binds existing detail to selected id and owner and discards ownership metadata", async () => {
    const { client, execute } = harness();
    execute.mockResolvedValue(
      response({ ...metadata, ownerTeacherUserId: userId }),
    );
    expect(await client.get(id)).toEqual({ ok: true, value: metadata });
    execute.mockResolvedValue(
      response({ ...metadata, ownerTeacherUserId: id }),
    );
    expect(await client.get(id)).toMatchObject({
      ok: false,
      error: { kind: "invalid-response" },
    });
    execute.mockResolvedValue(
      response({ ...metadata, id: userId, ownerTeacherUserId: userId }),
    );
    expect(await client.get(id)).toMatchObject({
      ok: false,
      error: { kind: "invalid-response" },
    });
  });
  it("rejects unexpected collection fields, malformed data and invalid cursors", async () => {
    const { client, execute } = harness();
    execute.mockResolvedValue(
      response({
        items: [
          {
            ...metadata,
            createdAt: "2026-10-26T08:00:00Z",
            ownerTeacherUserId: id,
          },
        ],
        nextCursor: null,
      }),
    );
    expect(await client.list()).toMatchObject({
      ok: false,
      error: { kind: "invalid-response" },
    });
    expect(await client.list("")).toMatchObject({
      ok: false,
      error: { kind: "invalid-input" },
    });
    expect(await client.get("bad-id")).toMatchObject({
      ok: false,
      error: { kind: "invalid-input" },
    });
    expect(execute).toHaveBeenCalledTimes(1);
  });
  it("fences late responses on account change and aborts an uncooperative executor", async () => {
    const { client, execute, changeSession } = harness();
    let finish!: (result: AuthenticatedRequestResult) => void;
    execute.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const pending = client.list();
    changeSession();
    finish(response({ items: [], nextCursor: null }));
    expect(await pending).toMatchObject({
      ok: false,
      error: { kind: "session" },
    });
    const next = harness();
    next.execute.mockImplementation(() => new Promise(() => {}));
    const controller = new AbortController();
    const aborted = next.client.list(undefined, controller.signal);
    controller.abort();
    expect(await aborted).toMatchObject({
      ok: false,
      error: { kind: "aborted" },
    });
  });
  it.each([403, 404, 503])(
    "preserves HTTP %s without a refresh loop or synthesized ownership",
    async (status) => {
      const { client, execute } = harness();
      execute.mockResolvedValue(response({ code: "DENIED" }, status));
      expect(await client.list()).toMatchObject({
        ok: false,
        error: { kind: "api", status },
      });
      expect(execute).toHaveBeenCalledTimes(1);
    },
  );
});
