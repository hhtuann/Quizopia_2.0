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
  type LoginResponse,
} from "../api/auth-api-client";
import type { AuthenticatedUser } from "../model/authenticated-user";
import { hasRole } from "../model/authenticated-user";
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
  retryVerification(): Promise<AuthApiResult<AuthenticatedUser>>;
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

  // Also serialize cookie mutations when the browser Web Locks API is absent.
  let authQueue: Promise<unknown> = Promise.resolve();
  function serializeAuth<T>(operation: () => Promise<T>): Promise<T> {
    const attempt = authQueue.then(operation);
    authQueue = attempt.then(
      () => undefined,
      () => undefined,
    );
    return attempt;
  }

  interface PendingVerification {
    readonly credentials: LoginResponse;
    readonly expiresAt: number;
    readonly generation: number;
  }
  let pendingVerification: PendingVerification | null = null;
  let verificationPromise: Promise<AuthApiResult<AuthenticatedUser>> | null =
    null;

  let authenticatedRequests!: AuthenticatedRequestExecutor;

  // The request deadline starts after lock acquisition. Bound acquisition too,
  // so another tab cannot indefinitely hold this service's local queue.
  async function lockForOperation<T>(
    generation: number,
    operation: () => Promise<T>,
    unavailable: T,
  ): Promise<T> {
    if (!runtime.isCurrentOperation(generation)) return unavailable;
    const controller = new AbortController();
    const unsubscribe = runtime.subscribeOperations(() => {
      if (!runtime.isCurrentOperation(generation)) controller.abort();
    });
    const timeout = setTimeout(() => controller.abort(), 10_000);
    let acquired = false;
    let onAbort!: () => void;
    const aborted = new Promise<T>((resolve) => {
      onAbort = () => {
        if (!acquired) resolve(unavailable);
      };
      controller.signal.addEventListener("abort", onAbort, { once: true });
    });
    try {
      return await Promise.race([
        withBrowserAuthLock(async () => {
          clearTimeout(timeout);
          // Even a non-cooperating lock implementation must not execute late.
          if (
            controller.signal.aborted ||
            !runtime.isCurrentOperation(generation)
          )
            return unavailable;
          acquired = true;
          unsubscribe();
          return operation();
        }, controller.signal),
        aborted,
      ]);
    } catch {
      return unavailable;
    } finally {
      clearTimeout(timeout);
      unsubscribe();
      controller.signal.removeEventListener("abort", onAbort);
    }
  }

  function canceledRequest(): AuthApiResult<never> {
    return {
      ok: false,
      error: { kind: "transport-error", error: { kind: "aborted" } },
    };
  }

  // Aborting also reaches fetch. The raced result ignores a late completion from
  // a transport that fails to honor cancellation; it does not undo server work.
  async function requestForOperation<T>(
    generation: number,
    request: (signal: AbortSignal) => Promise<AuthApiResult<T>>,
    timeoutMs?: number,
  ): Promise<AuthApiResult<T>> {
    if (!runtime.isCurrentOperation(generation))
      return clientStateFailure("session-changed");
    const controller = new AbortController();
    const unsubscribe = runtime.subscribeOperations(() => {
      if (!runtime.isCurrentOperation(generation)) controller.abort();
    });
    const timeout =
      timeoutMs === undefined
        ? undefined
        : setTimeout(() => controller.abort(), timeoutMs);
    let onAbort!: () => void;
    const aborted = new Promise<AuthApiResult<T>>((resolve) => {
      onAbort = () =>
        resolve({
          ok: false,
          error: { kind: "transport-error", error: { kind: "aborted" } },
        });
      controller.signal.addEventListener("abort", onAbort, { once: true });
    });
    try {
      const result = await Promise.race([request(controller.signal), aborted]);
      return runtime.isCurrentOperation(generation)
        ? result
        : clientStateFailure("session-changed");
    } catch (cause) {
      return {
        ok: false,
        error: { kind: "transport-error", error: { kind: "network", cause } },
      };
    } finally {
      clearTimeout(timeout);
      unsubscribe();
      controller.signal.removeEventListener("abort", onAbort);
    }
  }

  function hydrateCurrentUser(
    loginResponse: { readonly accessToken: string },
    generation: number,
  ) {
    return requestForOperation(
      generation,
      (signal) =>
        client.me(authenticatedRequests, loginResponse.accessToken, signal),
      10_000,
    );
  }

  async function refreshOperation(
    generation: number,
    sessionGeneration: number,
  ): Promise<RefreshResult> {
    const isCurrent = () =>
      runtime.isCurrentOperation(generation) &&
      runtime.getSessionGeneration() === sessionGeneration;
    return serializeAuth(() =>
      lockForOperation<RefreshResult>(
        generation,
        async () => {
          if (!isCurrent()) return { status: "no-session" };
          const refreshed = await requestForOperation(
            generation,
            (signal) => client.refresh(signal),
            10_000,
          );
          if (!isCurrent()) return { status: "no-session" };
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

          const expiresAt = Date.now() + refreshed.value.expiresIn * 1000;
          const currentUser = await hydrateCurrentUser(
            refreshed.value,
            generation,
          );
          if (!isCurrent()) return { status: "no-session" };
          if (!currentUser.ok) {
            if (
              !noSessionFromMeFailure(currentUser.error) &&
              runtime.getSnapshot().status === "bootstrapping"
            ) {
              pendingVerification = {
                credentials: refreshed.value,
                expiresAt,
                generation,
              };
              return {
                status: "transient-failure",
                cause: currentUser.error,
                verificationPending: true,
              };
            }
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
        },
        { status: "transient-failure" },
      ),
    );
  }

  async function verifyLogin(
    credentials: LoginResponse,
    expiresAt: number,
    generation: number,
  ): Promise<AuthApiResult<AuthenticatedUser>> {
    const currentUser = await hydrateCurrentUser(credentials, generation);
    if (!runtime.isCurrentOperation(generation)) {
      return clientStateFailure("session-changed");
    }
    if (!currentUser.ok) {
      if (noSessionFromMeFailure(currentUser.error)) {
        pendingVerification = null;
        runtime.clearLocalSession();
        await requestForOperation(
          runtime.getOperationGeneration(),
          (signal) => client.logout(signal),
          10_000,
        );
      } else {
        runtime.markVerificationUnavailable(generation);
        pendingVerification = {
          credentials,
          expiresAt,
          generation,
        };
      }
      return currentUser;
    }
    pendingVerification = null;
    return runtime.establishAuthenticatedSession(
      {
        accessToken: credentials.accessToken,
        user: currentUser.value,
      },
      generation,
    )
      ? currentUser
      : clientStateFailure("session-transition-failed");
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
        const generation = runtime.getOperationGeneration();
        await waitForBootstrap();
        if (!runtime.isCurrentOperation(generation))
          return clientStateFailure("session-changed");
        await refreshCoordinator.waitForIdle();
        if (!runtime.isCurrentOperation(generation))
          return clientStateFailure("session-changed");

        const enabled = await client.enableTeacher(authenticatedRequests);
        if (!enabled.ok) {
          return enabled;
        }
        if (!runtime.isCurrentOperation(generation))
          return clientStateFailure("session-changed");

        const refreshed = await refreshCoordinator.refresh();
        if (refreshed.status === "no-session") {
          return clientStateFailure("no-session");
        }
        if (!runtime.isCurrentOperation(generation))
          return clientStateFailure("session-changed");
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
      const generation = runtime.beginOperation();
      pendingVerification = null;
      if (runtime.getSnapshot().status === "verification-unavailable")
        runtime.prepareLoginHydration(generation);
      await waitForBootstrap();
      if (!runtime.isCurrentOperation(generation))
        return clientStateFailure("session-changed");
      // A login can supersede an already-running bootstrap. Its canceled result
      // must not mutate state; the current login settles the startup presentation.
      if (runtime.getSnapshot().status === "bootstrapping")
        runtime.completeBootstrapAsAnonymous();
      if (runtime.getSnapshot().status === "verification-unavailable") {
        // Bootstrap may have produced a candidate while this login awaited it.
        // The explicit account intent must supersede that candidate too.
        pendingVerification = null;
        runtime.prepareLoginHydration(generation);
      }
      await refreshCoordinator.waitForIdle();
      if (!runtime.isCurrentOperation(generation))
        return clientStateFailure("session-changed");
      return serializeAuth(() =>
        lockForOperation(
          generation,
          async () => {
            if (!runtime.isCurrentOperation(generation))
              return clientStateFailure("session-changed");
            const loggedIn = await requestForOperation(
              generation,
              (signal) => client.login(input, signal),
              10_000,
            );
            if (!runtime.isCurrentOperation(generation))
              return clientStateFailure("session-changed");
            if (!loggedIn.ok) {
              return loggedIn;
            }
            pendingVerification = null;
            runtime.prepareLoginHydration(generation);
            return verifyLogin(
              loggedIn.value,
              Date.now() + loggedIn.value.expiresIn * 1000,
              generation,
            );
          },
          canceledRequest(),
        ),
      );
    },

    retryVerification() {
      if (verificationPromise !== null) {
        return verificationPromise;
      }
      const pending = pendingVerification;
      if (pending !== null)
        runtime.setVerificationPending(true, pending.generation);
      const attempt = serializeAuth(() =>
        lockForOperation(
          pending?.generation ?? runtime.getOperationGeneration(),
          async () => {
            if (
              pending === null ||
              pending !== pendingVerification ||
              runtime.getSnapshot().status !== "verification-unavailable" ||
              !runtime.isCurrentOperation(pending.generation)
            ) {
              return clientStateFailure("no-pending-verification");
            }
            let credentials = pending.credentials;
            let expiresAt = pending.expiresAt;
            if (Date.now() >= expiresAt) {
              // One explicit recovery attempt; ten seconds matches hydration's
              // interactive deadline. No automatic retry of uncertain rotation.
              const refreshed = await requestForOperation(
                pending.generation,
                (signal) => client.refresh(signal),
                10_000,
              );
              if (!runtime.isCurrentOperation(pending.generation)) {
                return clientStateFailure("session-changed");
              }
              if (!refreshed.ok) {
                if (
                  refreshed.error.kind === "api-error" &&
                  refreshed.error.error.status === 401 &&
                  refreshed.error.error.code === "AUTH_REFRESH_FAILED"
                ) {
                  pendingVerification = null;
                  runtime.clearLocalSession();
                }
                return refreshed;
              }
              credentials = refreshed.value;
              expiresAt = Date.now() + credentials.expiresIn * 1000;
            }
            return verifyLogin(credentials, expiresAt, pending.generation);
          },
          canceledRequest(),
        ),
      );
      verificationPromise = attempt;
      const clear = () => {
        if (verificationPromise === attempt) {
          verificationPromise = null;
          if (pending !== null)
            runtime.setVerificationPending(false, pending.generation);
        }
      };
      void attempt.then(clear, clear);
      return attempt;
    },

    logout() {
      if (logoutPromise !== null) {
        return logoutPromise;
      }

      const attempt = (async () => {
        const previousGeneration = runtime.getOperationGeneration();
        await refreshCoordinator.waitForIdle();
        if (!runtime.isCurrentOperation(previousGeneration))
          return clientStateFailure("session-changed");
        const previousPending = pendingVerification;
        const generation = runtime.beginOperation();
        return serializeAuth(() =>
          lockForOperation(
            generation,
            async () => {
              if (!runtime.isCurrentOperation(generation))
                return clientStateFailure("session-changed");
              const result = await requestForOperation(
                generation,
                (signal) => client.logout(signal),
                10_000,
              );
              if (!runtime.isCurrentOperation(generation))
                return clientStateFailure("session-changed");
              if (result.ok) {
                pendingVerification = null;
                runtime.clearLocalSession();
              } else if (
                previousPending !== null &&
                pendingVerification === previousPending &&
                runtime.getSnapshot().status === "verification-unavailable"
              ) {
                // An unsuccessful explicit logout must leave manual verification
                // available, while keeping the canceled request's epoch obsolete.
                pendingVerification = { ...previousPending, generation };
              }
              return result;
            },
            canceledRequest(),
          ),
        );
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
