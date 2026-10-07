import {
  createAuthenticatedRequestExecutor,
  type AuthenticatedRequestExecutor,
} from "../../../lib/api/authenticated-request";
import {
  createFetchHttpTransport,
  type HttpTransport,
} from "../../../lib/api/http-transport";
import {
  createAuthApiClient,
  type AuthApiFailure,
  type AuthApiResult,
} from "../api/auth-api-client";
import type { AuthenticatedUser } from "../model/authenticated-user";
import { hasRole } from "../model/authenticated-user";
import { hasAuthenticatedIdentity } from "../model/session-state";
import { createAccessTokenVault } from "./access-token-vault";
import {
  createRefreshCoordinator,
  type RefreshCoordinator,
} from "./refresh-coordinator";
import type { RefreshResult } from "./refresh-result";
import { createSessionRuntime, type SessionRuntime } from "./session-runtime";
import { withBrowserAuthLock } from "./browser-auth-lock";

export interface AuthSessionService {
  readonly authenticatedRequests: AuthenticatedRequestExecutor;
  readonly runtime: SessionRuntime;
  bootstrap(): Promise<RefreshResult>;
  confirmVerification(input: {
    readonly otp: string;
    readonly username: string;
  }): Promise<AuthApiResult<void>>;
  enableTeacher(): Promise<TeacherEnablementResult>;
  login(input: {
    readonly identifier: string;
    readonly password: string;
  }): Promise<AuthApiResult<AuthenticatedUser>>;
  logout(): Promise<AuthApiResult<void>>;
  register(input: {
    readonly email: string;
    readonly password: string;
    readonly username: string;
  }): Promise<AuthApiResult<"VERIFICATION_REQUIRED">>;
  requestVerification(input: {
    readonly username: string;
  }): Promise<AuthApiResult<"VERIFICATION_REQUEST_ACCEPTED">>;
}

export type TeacherEnablementFailure =
  | AuthApiFailure
  | {
      readonly kind: "session-update-failure";
      readonly cause?: unknown;
      readonly reason: "refresh-failed" | "teacher-role-not-reflected";
    };

export type TeacherEnablementResult =
  | { readonly ok: true; readonly value: AuthenticatedUser }
  | { readonly ok: false; readonly error: TeacherEnablementFailure };

function clientStateFailure(reason: string): AuthApiResult<never> {
  return {
    ok: false,
    error: { kind: "authentication-failure", reason },
  };
}

function noSessionFromMeFailure(error: AuthApiFailure): boolean {
  return (
    error.kind === "authentication-failure" ||
    (error.kind === "api-error" &&
      (error.error.status === 401 || error.error.status === 403)) ||
    (error.kind === "unexpected-response" &&
      (error.status === 401 || error.status === 403))
  );
}

export function createAuthSessionService(
  options: {
    readonly apiBaseUrl?: string;
    readonly transport?: HttpTransport;
  } = {},
): AuthSessionService {
  const transport = options.transport ?? createFetchHttpTransport();
  const accessTokenVault = createAccessTokenVault();
  const runtime = createSessionRuntime(accessTokenVault);
  const client = createAuthApiClient({
    apiBaseUrl: options.apiBaseUrl,
    transport,
  });

  let authenticatedRequests!: AuthenticatedRequestExecutor;

  async function hydrateCurrentUser(loginResponse: {
    readonly accessToken: string;
  }): Promise<AuthApiResult<AuthenticatedUser>> {
    return client.me(authenticatedRequests, loginResponse.accessToken);
  }

  async function refreshOperation(): Promise<RefreshResult> {
    return withBrowserAuthLock(async () => {
      const refreshed = await client.refresh();
      if (!refreshed.ok) {
        if (
          refreshed.error.kind === "api-error" &&
          refreshed.error.error.status === 401 &&
          refreshed.error.error.code === "AUTH_REFRESH_FAILED"
        ) {
          return { status: "no-session" };
        }
        return { status: "transient-failure", cause: refreshed.error };
      }

      const currentUser = await hydrateCurrentUser(refreshed.value);
      if (!currentUser.ok) {
        return noSessionFromMeFailure(currentUser.error)
          ? { status: "no-session" }
          : { status: "transient-failure", cause: currentUser.error };
      }

      return {
        status: "refreshed",
        session: {
          accessToken: refreshed.value.accessToken,
          user: currentUser.value,
        },
      };
    });
  }

  const refreshCoordinator: RefreshCoordinator = createRefreshCoordinator(
    runtime,
    refreshOperation,
  );
  authenticatedRequests = createAuthenticatedRequestExecutor({
    accessTokenVault,
    refreshCoordinator,
    sessionRuntime: runtime,
    transport,
  });

  let bootstrapPromise: Promise<RefreshResult> | null = null;
  let teacherEnablementPromise: Promise<TeacherEnablementResult> | null = null;
  let logoutPromise: Promise<AuthApiResult<void>> | null = null;
  function bootstrap(): Promise<RefreshResult> {
    if (runtime.getSnapshot().status !== "bootstrapping") {
      return Promise.resolve({ status: "no-session" });
    }
    if (bootstrapPromise === null) {
      bootstrapPromise = refreshCoordinator.bootstrap();
    }
    return bootstrapPromise;
  }

  async function waitForBootstrap() {
    if (runtime.getSnapshot().status === "bootstrapping") {
      await bootstrap();
    }
  }

  const service: AuthSessionService = {
    authenticatedRequests,
    runtime,

    bootstrap,

    confirmVerification(input) {
      return client.confirmVerification(input);
    },

    enableTeacher() {
      if (teacherEnablementPromise !== null) {
        return teacherEnablementPromise;
      }

      const attempt = (async (): Promise<TeacherEnablementResult> => {
        await waitForBootstrap();
        await refreshCoordinator.waitForIdle();

        const enabled = await client.enableTeacher(authenticatedRequests);
        if (!enabled.ok) {
          return enabled;
        }

        const refreshed = await refreshCoordinator.refresh();
        if (refreshed.status === "no-session") {
          return clientStateFailure("no-session");
        }
        if (refreshed.status === "transient-failure") {
          return {
            ok: false,
            error: {
              kind: "session-update-failure",
              cause: refreshed.cause,
              reason: "refresh-failed",
            },
          };
        }
        if (!hasRole(refreshed.session.user, "TEACHER")) {
          return {
            ok: false,
            error: {
              kind: "session-update-failure",
              reason: "teacher-role-not-reflected",
            },
          };
        }

        return { ok: true, value: refreshed.session.user };
      })();

      teacherEnablementPromise = attempt;
      const clearTeacherEnablement = () => {
        if (teacherEnablementPromise === attempt) {
          teacherEnablementPromise = null;
        }
      };
      void attempt.then(clearTeacherEnablement, clearTeacherEnablement);
      return attempt;
    },

    async login(input) {
      await waitForBootstrap();
      await refreshCoordinator.waitForIdle();

      const loggedIn = await withBrowserAuthLock(() => client.login(input));
      if (!loggedIn.ok) {
        return loggedIn;
      }

      if (hasAuthenticatedIdentity(runtime.getSnapshot())) {
        runtime.clearLocalSession();
      }

      const currentUser = await hydrateCurrentUser(loggedIn.value);
      if (!currentUser.ok) {
        runtime.clearLocalSession();
        await withBrowserAuthLock(() => client.logout());
        return currentUser;
      }

      if (
        !runtime.establishAuthenticatedSession({
          accessToken: loggedIn.value.accessToken,
          user: currentUser.value,
        })
      ) {
        runtime.clearLocalSession();
        return clientStateFailure("session-transition-failed");
      }

      return currentUser;
    },

    logout() {
      if (logoutPromise !== null) {
        return logoutPromise;
      }

      const attempt = (async () => {
        await waitForBootstrap();
        await refreshCoordinator.waitForIdle();
        const result = await withBrowserAuthLock(() => client.logout());
        if (result.ok) {
          runtime.clearLocalSession();
        }
        return result;
      })();
      logoutPromise = attempt;
      const clearLogout = () => {
        if (logoutPromise === attempt) {
          logoutPromise = null;
        }
      };
      void attempt.then(clearLogout, clearLogout);
      return attempt;
    },

    register(input) {
      return client.register(input);
    },

    requestVerification(input) {
      return client.requestVerification(input);
    },
  };

  return Object.freeze(service);
}
