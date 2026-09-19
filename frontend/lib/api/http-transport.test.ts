import { describe, expect, it, vi } from "vitest";
import { createFetchHttpTransport } from "./http-transport";

describe("fetch HTTP transport", () => {
  it("normalizes a successful JSON response", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    fetchMock.mockResolvedValue(
      new Response('{"ready":true}', {
        headers: { "content-type": "application/json; charset=utf-8" },
        status: 200,
      }),
    );
    const transport = createFetchHttpTransport(fetchMock);

    const result = await transport.execute({ target: "/resource" });

    expect(result).toEqual({
      kind: "response",
      response: {
        body: { kind: "json", value: { ready: true } },
        headers: expect.any(Headers),
        ok: true,
        status: 200,
      },
    });
  });

  it("keeps non-success status and text bodies observable", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    fetchMock.mockResolvedValue(
      new Response("Temporarily unavailable", {
        headers: { "content-type": "text/plain" },
        status: 503,
      }),
    );
    const transport = createFetchHttpTransport(fetchMock);

    const result = await transport.execute({ target: "/resource" });

    expect(result).toMatchObject({
      kind: "response",
      response: {
        body: { kind: "text", value: "Temporarily unavailable" },
        ok: false,
        status: 503,
      },
    });
  });

  it("handles no-content responses without parsing a body", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const transport = createFetchHttpTransport(fetchMock);

    const result = await transport.execute({ target: "/resource" });

    expect(result).toMatchObject({
      kind: "response",
      response: { body: { kind: "empty" }, ok: true, status: 204 },
    });
  });

  it("normalizes network and invalid JSON failures", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    const networkCause = new TypeError("Network unavailable");
    fetchMock.mockRejectedValueOnce(networkCause).mockResolvedValueOnce(
      new Response("not-json", {
        headers: { "content-type": "application/json" },
        status: 200,
      }),
    );
    const transport = createFetchHttpTransport(fetchMock);

    const networkResult = await transport.execute({ target: "/resource" });
    const invalidResponseResult = await transport.execute({
      target: "/resource",
    });

    expect(networkResult).toEqual({
      kind: "transport-error",
      error: { kind: "network", cause: networkCause },
    });
    expect(invalidResponseResult).toMatchObject({
      kind: "transport-error",
      error: { kind: "invalid-response" },
    });
  });

  it("classifies a standard AbortSignal rejection as aborted", async () => {
    const controller = new AbortController();
    controller.abort();
    const fetchMock = vi.fn<typeof fetch>();
    fetchMock.mockRejectedValue(controller.signal.reason);
    const transport = createFetchHttpTransport(fetchMock);

    const result = await transport.execute({
      signal: controller.signal,
      target: "/resource",
    });

    expect(result).toEqual({
      kind: "transport-error",
      error: { kind: "aborted", cause: controller.signal.reason },
    });
  });

  it("classifies and preserves a custom string abort reason", async () => {
    const controller = new AbortController();
    controller.abort("Caller canceled");
    const fetchMock = vi.fn<typeof fetch>();
    fetchMock.mockRejectedValue(controller.signal.reason);
    const transport = createFetchHttpTransport(fetchMock);

    const result = await transport.execute({
      signal: controller.signal,
      target: "/resource",
    });

    expect(result).toEqual({
      kind: "transport-error",
      error: { kind: "aborted", cause: "Caller canceled" },
    });
  });

  it("classifies and preserves a custom Error abort reason", async () => {
    const abortReason = new Error("Caller canceled");
    const controller = new AbortController();
    controller.abort(abortReason);
    const fetchMock = vi.fn<typeof fetch>();
    fetchMock.mockRejectedValue(controller.signal.reason);
    const transport = createFetchHttpTransport(fetchMock);

    const result = await transport.execute({
      signal: controller.signal,
      target: "/resource",
    });

    expect(result).toEqual({
      kind: "transport-error",
      error: { kind: "aborted", cause: abortReason },
    });
  });

  it("keeps a genuine rejection classified as network while its signal is active", async () => {
    const networkCause = new Error("Network unavailable");
    const controller = new AbortController();
    const fetchMock = vi.fn<typeof fetch>();
    fetchMock.mockRejectedValue(networkCause);
    const transport = createFetchHttpTransport(fetchMock);

    const result = await transport.execute({
      signal: controller.signal,
      target: "/resource",
    });

    expect(controller.signal.aborted).toBe(false);
    expect(result).toEqual({
      kind: "transport-error",
      error: { kind: "network", cause: networkCause },
    });
  });

  it("passes explicit cookie credentials, headers, body, and AbortSignal to fetch", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const transport = createFetchHttpTransport(fetchMock);
    const controller = new AbortController();

    await transport.execute({
      body: "request-body",
      credentials: "include",
      headers: { "content-type": "text/plain" },
      method: "POST",
      signal: controller.signal,
      target: "/caller-supplied-target",
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [target, init] = fetchMock.mock.calls[0] ?? [];
    expect(target).toBe("/caller-supplied-target");
    expect(init).toMatchObject({
      body: "request-body",
      credentials: "include",
      method: "POST",
      signal: controller.signal,
    });
    expect(new Headers(init?.headers).get("content-type")).toBe("text/plain");
  });
});
