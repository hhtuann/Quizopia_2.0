import { describe, expect, it, vi } from "vitest";
import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import type {
  HttpRequest,
  HttpTransport,
  HttpTransportResult,
} from "../../../lib/api/http-transport";
import { createAuthApiClient } from "./auth-api-client";

function jsonResponse(status: number, value: unknown): HttpTransportResult {
  return {
    kind: "response",
    response: {
      body: { kind: "json", value },
      headers: new Headers({ "content-type": "application/json" }),
      ok: status >= 200 && status < 300,
      status,
    },
  };
}

function emptyResponse(status: number): HttpTransportResult {
  return {
    kind: "response",
    response: {
      body: { kind: "empty" },
      headers: new Headers(),
      ok: status >= 200 && status < 300,
      status,
    },
  };
}

function requestBody(request: HttpRequest): unknown {
  return request.body === undefined || request.body === null
    ? undefined
    : JSON.parse(String(request.body));
}

describe("auth API client", () => {
  it("sends the exact registration DTO to the configured public API origin", async () => {
    const execute = vi.fn(async (request: HttpRequest) => {
      expect(request.target).toBe("https://gateway.example/api/auth/register");
      expect(request.method).toBe("POST");
      expect(request.credentials).toBeUndefined();
      expect(requestBody(request)).toEqual({
        email: "learner01@gmail.com",
        password: "secret",
        username: "learner01",
      });
      expect(requestBody(request)).not.toHaveProperty("role");
      return jsonResponse(202, { status: "VERIFICATION_REQUIRED" });
    });
    const client = createAuthApiClient({
      apiBaseUrl: "https://gateway.example/",
      transport: { execute },
    });

    await expect(
      client.register({
        email: "learner01@gmail.com",
        password: "secret",
        username: "learner01",
      }),
    ).resolves.toEqual({ ok: true, value: "VERIFICATION_REQUIRED" });
    expect(execute).toHaveBeenCalledTimes(1);
  });

  it("uses browser credentials for login, refresh, and logout without modeling a refresh token", async () => {
    const requests: HttpRequest[] = [];
    const responses = [
      jsonResponse(200, {
        accessToken: "login-access",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
      jsonResponse(200, {
        accessToken: "refresh-access",
        expiresIn: 299,
        tokenType: "Bearer",
      }),
      emptyResponse(204),
    ];
    const transport: HttpTransport = {
      async execute(request) {
        requests.push(request);
        return responses.shift()!;
      },
    };
    const client = createAuthApiClient({
      apiBaseUrl: "https://gateway.example",
      transport,
    });

    await client.login({
      identifier: "learner01@gmail.com",
      password: "secret",
    });
    await client.refresh();
    await client.logout();

    expect(requests.map((request) => request.credentials)).toEqual([
      "include",
      "include",
      "include",
    ]);
    expect(requestBody(requests[0]!)).toEqual({
      identifier: "learner01@gmail.com",
      password: "secret",
    });
    expect(requestBody(requests[1]!)).toBeUndefined();
    expect(requestBody(requests[2]!)).toBeUndefined();
    expect(JSON.stringify(requests)).not.toContain("refreshToken");
  });

  it("uses username as the verification subject and sends only a six-digit OTP on confirm", async () => {
    const requests: HttpRequest[] = [];
    const responses = [
      jsonResponse(202, { status: "VERIFICATION_REQUEST_ACCEPTED" }),
      emptyResponse(204),
    ];
    const client = createAuthApiClient({
      apiBaseUrl: "https://gateway.example",
      transport: {
        async execute(request) {
          requests.push(request);
          return responses.shift()!;
        },
      },
    });

    await expect(
      client.requestVerification({ username: "learner01" }),
    ).resolves.toEqual({
      ok: true,
      value: "VERIFICATION_REQUEST_ACCEPTED",
    });
    await expect(
      client.confirmVerification({ otp: "012345", username: "learner01" }),
    ).resolves.toEqual({ ok: true, value: undefined });

    expect(String(requests[0]?.target)).toBe(
      "https://gateway.example/api/auth/email-verification/request",
    );
    expect(requestBody(requests[0]!)).toEqual({ username: "learner01" });
    expect(requests[0]?.credentials).toBeUndefined();
    expect(String(requests[1]?.target)).toBe(
      "https://gateway.example/api/auth/email-verification/confirm",
    );
    expect(requestBody(requests[1]!)).toEqual({
      otp: "012345",
      username: "learner01",
    });
    expect(requests[1]?.credentials).toBeUndefined();
  });

  it("parses the stable API error envelope and preserves its code", async () => {
    const client = createAuthApiClient({
      transport: {
        async execute() {
          return jsonResponse(401, {
            code: "AUTH_INVALID_CREDENTIALS",
            message: "Invalid credentials.",
            path: "/api/auth/login",
            status: 401,
            traceId: "trace-1",
          });
        },
      },
    });

    await expect(
      client.login({ identifier: "learner01", password: "wrong" }),
    ).resolves.toEqual({
      ok: false,
      error: {
        kind: "api-error",
        error: {
          code: "AUTH_INVALID_CREDENTIALS",
          message: "Invalid credentials.",
          path: "/api/auth/login",
          status: 401,
          traceId: "trace-1",
        },
      },
    });
  });

  it("rejects malformed successful login payloads instead of trusting them", async () => {
    const client = createAuthApiClient({
      transport: {
        async execute() {
          return jsonResponse(200, {
            accessToken: "token",
            expiresIn: "300",
            tokenType: "Bearer",
          });
        },
      },
    });

    await expect(
      client.login({ identifier: "learner01", password: "secret" }),
    ).resolves.toEqual({
      ok: false,
      error: { kind: "unexpected-response", status: 200 },
    });
  });

  it("hydrates authoritative current-user fields with the supplied access token", async () => {
    const executeOnceWithAccessToken = vi.fn<
      AuthenticatedRequestExecutor["executeOnceWithAccessToken"]
    >(async (): Promise<AuthenticatedRequestResult> => ({
      kind: "response",
      response: {
        body: {
          kind: "json",
          value: {
            email: "learner01@gmail.com",
            id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
            roles: ["STUDENT", "TEACHER"],
            username: "learner01",
          },
        },
        headers: new Headers(),
        ok: true,
        status: 200,
      },
    }));
    const authenticatedRequests: AuthenticatedRequestExecutor = {
      execute: vi.fn(),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken,
    };
    const client = createAuthApiClient({
      transport: { execute: vi.fn() },
    });

    const result = await client.me(authenticatedRequests, "current-access");

    expect(result).toEqual({
      ok: true,
      value: {
        email: "learner01@gmail.com",
        id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
        roles: ["STUDENT", "TEACHER"],
        username: "learner01",
      },
    });
    expect(executeOnceWithAccessToken).toHaveBeenCalledTimes(1);
    const request =
      executeOnceWithAccessToken.mock.calls[0]![0].createRequest();
    expect(executeOnceWithAccessToken.mock.calls[0]![1]).toBe("current-access");
    expect(request.target).toBe("/api/auth/me");
    expect(new Headers(request.headers).has("authorization")).toBe(false);
  });

  it("rejects unknown current-user roles", async () => {
    const authenticatedRequests: AuthenticatedRequestExecutor = {
      execute: vi.fn(),
      executeOnce: vi.fn(),
      executeOnceWithAccessToken: vi.fn(
        async (): Promise<AuthenticatedRequestResult> => ({
          kind: "response",
          response: {
            body: {
              kind: "json",
              value: {
                email: "learner01@gmail.com",
                id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
                roles: ["OWNER"],
                username: "learner01",
              },
            },
            headers: new Headers(),
            ok: true,
            status: 200,
          },
        }),
      ),
    };
    const client = createAuthApiClient({
      transport: { execute: vi.fn() },
    });

    await expect(
      client.me(authenticatedRequests, "current-access"),
    ).resolves.toEqual({
      ok: false,
      error: { kind: "unexpected-response", status: 200 },
    });
  });
});
