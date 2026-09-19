import { describe, expect, it, vi } from "vitest";
import { createAuthenticatedUser } from "../model/authenticated-user";
import { createAccessTokenVault } from "./access-token-vault";
import { createRefreshCoordinator } from "./refresh-coordinator";
import type { RefreshOperation, RefreshResult } from "./refresh-result";
import { createSessionRuntime } from "./session-runtime";

const userId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, reject, resolve };
}

function session(accessToken: string) {
  return {
    accessToken,
    user: createAuthenticatedUser({ userId, roles: ["STUDENT"] }),
  };
}

function authenticatedRuntime() {
  const vault = createAccessTokenVault();
  const runtime = createSessionRuntime(vault);
  runtime.establishAuthenticatedSession(session("initial-access-token"));
  return { runtime, vault };
}

describe("refresh coordinator", () => {
  it("shares one in-flight success across waiters and resets afterward", async () => {
    const { runtime, vault } = authenticatedRuntime();
    const firstAttempt = deferred<RefreshResult>();
    const refreshOperation = vi.fn<RefreshOperation>();
    refreshOperation
      .mockReturnValueOnce(firstAttempt.promise)
      .mockResolvedValueOnce({
        status: "refreshed",
        session: session("third-access-token"),
      });
    const coordinator = createRefreshCoordinator(runtime, refreshOperation);

    const first = coordinator.refresh();
    const second = coordinator.refresh();
    const third = coordinator.refresh();

    expect(first).toBe(second);
    expect(second).toBe(third);
    expect(refreshOperation).toHaveBeenCalledTimes(1);
    expect(runtime.getSnapshot().status).toBe("refreshing");

    firstAttempt.resolve({
      status: "refreshed",
      session: session("replacement-access-token"),
    });
    const results = await Promise.all([first, second, third]);

    expect(results[0]).toBe(results[1]);
    expect(results[1]).toBe(results[2]);
    expect(vault.read()).toBe("replacement-access-token");

    await coordinator.refresh();
    expect(refreshOperation).toHaveBeenCalledTimes(2);
    expect(vault.read()).toBe("third-access-token");
  });

  it("resets after transient returned and thrown failures", async () => {
    const { runtime, vault } = authenticatedRuntime();
    const firstCause = new Error("Temporary failure");
    const secondCause = new Error("Rejected operation");
    const refreshOperation = vi.fn<RefreshOperation>();
    refreshOperation
      .mockResolvedValueOnce({ status: "transient-failure", cause: firstCause })
      .mockRejectedValueOnce(secondCause);
    const coordinator = createRefreshCoordinator(runtime, refreshOperation);

    await expect(coordinator.refresh()).resolves.toEqual({
      status: "transient-failure",
      cause: firstCause,
    });
    expect(runtime.getSnapshot().status).toBe("authenticated");
    expect(vault.read()).toBe("initial-access-token");

    await expect(coordinator.refresh()).resolves.toEqual({
      status: "transient-failure",
      cause: secondCause,
    });
    expect(refreshOperation).toHaveBeenCalledTimes(2);
    expect(runtime.getSnapshot().status).toBe("authenticated");
    expect(vault.read()).toBe("initial-access-token");
  });

  it("expires the runtime only when the operation reports no session", async () => {
    const { runtime, vault } = authenticatedRuntime();
    const refreshOperation = vi.fn<RefreshOperation>();
    refreshOperation.mockResolvedValue({ status: "no-session" });
    const coordinator = createRefreshCoordinator(runtime, refreshOperation);

    await expect(coordinator.refresh()).resolves.toEqual({
      status: "no-session",
    });

    expect(refreshOperation).toHaveBeenCalledTimes(1);
    expect(runtime.getSnapshot()).toEqual({ status: "session-expired" });
    expect(vault.read()).toBeNull();
  });
});
