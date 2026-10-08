import { z } from "zod";
import type {
  AuthenticatedRequestExecutor,
  AuthenticatedRequestResult,
} from "../../../lib/api/authenticated-request";
import type {
  HttpResponse,
  HttpTransport,
  HttpTransportError,
  HttpTransportResult,
} from "../../../lib/api/http-transport";
import { publicApiTarget } from "../../../lib/api/public-api";
import {
  AUTH_ROLES,
  createAuthenticatedUser,
  type AuthenticatedUser,
} from "../model/authenticated-user";

const apiErrorSchema = z
  .object({
    code: z.string(),
    message: z.string(),
    path: z.string(),
    status: z.number().int(),
    traceId: z.string().nullable(),
  })
  .strict();

const loginResponseSchema = z
  .object({
    accessToken: z.string().min(1),
    expiresIn: z.number().int().positive(),
    tokenType: z.literal("Bearer"),
  })
  .strict();

const currentUserSchema = z
  .object({
    email: z.string().min(1),
    id: z.string().min(1),
    roles: z.array(z.enum(AUTH_ROLES)),
    username: z.string().min(1),
  })
  .strict();

const registrationStatusSchema = z
  .object({ status: z.literal("VERIFICATION_REQUIRED") })
  .strict();

const verificationRequestStatusSchema = z
  .object({ status: z.literal("VERIFICATION_REQUEST_ACCEPTED") })
  .strict();

export interface ApiErrorEnvelope {
  readonly code: string;
  readonly message: string;
  readonly path: string;
  readonly status: number;
  readonly traceId: string | null;
}

export type AuthApiFailure =
  | { readonly kind: "api-error"; readonly error: ApiErrorEnvelope }
  | {
      readonly kind: "authentication-failure";
      readonly reason: string;
    }
  | {
      readonly kind: "transport-error";
      readonly error: HttpTransportError;
    }
  | {
      readonly kind: "unexpected-response";
      readonly status: number;
    };

export type AuthApiResult<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: AuthApiFailure };

export interface LoginResponse {
  readonly accessToken: string;
  readonly expiresIn: number;
  readonly tokenType: "Bearer";
}

export interface AuthApiClient {
  confirmVerification(input: {
    readonly otp: string;
    readonly username: string;
  }): Promise<AuthApiResult<void>>;
  login(input: {
    readonly identifier: string;
    readonly password: string;
  }): Promise<AuthApiResult<LoginResponse>>;
  enableTeacher(
    authenticatedRequests: AuthenticatedRequestExecutor,
  ): Promise<AuthApiResult<void>>;
  logout(): Promise<AuthApiResult<void>>;
  me(
    authenticatedRequests: AuthenticatedRequestExecutor,
    accessToken: string,
  ): Promise<AuthApiResult<AuthenticatedUser>>;
  refresh(): Promise<AuthApiResult<LoginResponse>>;
  register(input: {
    readonly email: string;
    readonly password: string;
    readonly username: string;
  }): Promise<AuthApiResult<"VERIFICATION_REQUIRED">>;
  requestVerification(input: {
    readonly username: string;
  }): Promise<AuthApiResult<"VERIFICATION_REQUEST_ACCEPTED">>;
}

function jsonHeaders(): HeadersInit {
  return {
    accept: "application/json",
    "content-type": "application/json",
  };
}

function parseFailure(response: HttpResponse): AuthApiResult<never> {
  if (response.body.kind === "json") {
    const parsed = apiErrorSchema.safeParse(response.body.value);
    if (parsed.success && parsed.data.status === response.status) {
      return {
        ok: false,
        error: { kind: "api-error", error: parsed.data },
      };
    }
  }

  return {
    ok: false,
    error: { kind: "unexpected-response", status: response.status },
  };
}

function normalizeTransportResult(
  result: HttpTransportResult,
): AuthApiResult<HttpResponse> {
  if (result.kind === "transport-error") {
    return {
      ok: false,
      error: { kind: "transport-error", error: result.error },
    };
  }

  return { ok: true, value: result.response };
}

function normalizeAuthenticatedResult(
  result: AuthenticatedRequestResult,
): AuthApiResult<HttpResponse> {
  if (result.kind === "transport-error") {
    return {
      ok: false,
      error: { kind: "transport-error", error: result.error },
    };
  }
  if (result.kind === "authentication-failure") {
    return {
      ok: false,
      error: { kind: "authentication-failure", reason: result.reason },
    };
  }
  if (result.kind === "refresh-failure") {
    return {
      ok: false,
      error: { kind: "authentication-failure", reason: "refresh-failure" },
    };
  }

  return { ok: true, value: result.response };
}

function expectJson<T>(
  response: HttpResponse,
  expectedStatus: number,
  schema: z.ZodType<T>,
): AuthApiResult<T> {
  if (!response.ok) {
    return parseFailure(response);
  }
  if (response.status !== expectedStatus || response.body.kind !== "json") {
    return {
      ok: false,
      error: { kind: "unexpected-response", status: response.status },
    };
  }

  const parsed = schema.safeParse(response.body.value);
  return parsed.success
    ? { ok: true, value: parsed.data }
    : {
        ok: false,
        error: { kind: "unexpected-response", status: response.status },
      };
}

function expectEmpty(
  response: HttpResponse,
  expectedStatus: number,
): AuthApiResult<void> {
  if (!response.ok) {
    return parseFailure(response);
  }
  if (response.status !== expectedStatus || response.body.kind !== "empty") {
    return {
      ok: false,
      error: { kind: "unexpected-response", status: response.status },
    };
  }

  return { ok: true, value: undefined };
}

export function createAuthApiClient(options: {
  readonly apiBaseUrl?: string;
  readonly transport: HttpTransport;
}): AuthApiClient {
  const { apiBaseUrl, transport } = options;
  const target = (path: string) => publicApiTarget(path, apiBaseUrl);

  async function executeJson<T>(
    path: string,
    input: unknown,
    expectedStatus: number,
    schema: z.ZodType<T>,
    credentials?: RequestCredentials,
  ): Promise<AuthApiResult<T>> {
    const result = normalizeTransportResult(
      await transport.execute({
        body: JSON.stringify(input),
        credentials,
        headers: jsonHeaders(),
        method: "POST",
        target: target(path),
      }),
    );
    return result.ok
      ? expectJson(result.value, expectedStatus, schema)
      : result;
  }

  const client: AuthApiClient = {
    async confirmVerification(input) {
      const result = normalizeTransportResult(
        await transport.execute({
          body: JSON.stringify(input),
          headers: jsonHeaders(),
          method: "POST",
          target: target("/api/auth/email-verification/confirm"),
        }),
      );
      return result.ok ? expectEmpty(result.value, 204) : result;
    },

    login(input) {
      return executeJson(
        "/api/auth/login",
        input,
        200,
        loginResponseSchema,
        "include",
      );
    },

    async enableTeacher(authenticatedRequests) {
      const result = normalizeAuthenticatedResult(
        await authenticatedRequests.execute({
          createRequest: () => ({
            headers: { accept: "application/json" },
            method: "POST",
            target: target("/api/auth/teacher-enablement"),
          }),
        }),
      );
      return result.ok ? expectEmpty(result.value, 204) : result;
    },

    async logout() {
      const result = normalizeTransportResult(
        await transport.execute({
          credentials: "include",
          headers: { accept: "application/json" },
          method: "POST",
          target: target("/api/auth/logout"),
        }),
      );
      return result.ok ? expectEmpty(result.value, 204) : result;
    },

    async me(authenticatedRequests, accessToken) {
      const result = normalizeAuthenticatedResult(
        await authenticatedRequests.executeOnceWithAccessToken(
          {
            createRequest: () => ({
              headers: { accept: "application/json" },
              method: "GET",
              target: target("/api/auth/me"),
            }),
          },
          accessToken,
        ),
      );
      if (!result.ok) {
        return result;
      }

      const parsed = expectJson(result.value, 200, currentUserSchema);
      if (!parsed.ok) {
        return parsed;
      }

      return {
        ok: true,
        value: createAuthenticatedUser(parsed.value),
      };
    },

    refresh() {
      return executeJson(
        "/api/auth/refresh",
        undefined,
        200,
        loginResponseSchema,
        "include",
      );
    },

    async register(input) {
      const result = await executeJson(
        "/api/auth/register",
        input,
        202,
        registrationStatusSchema,
      );
      return result.ok ? { ok: true, value: result.value.status } : result;
    },

    async requestVerification(input) {
      const result = await executeJson(
        "/api/auth/email-verification/request",
        input,
        202,
        verificationRequestStatusSchema,
      );
      return result.ok ? { ok: true, value: result.value.status } : result;
    },
  };

  return Object.freeze(client);
}
