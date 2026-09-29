import { describe, expect, it, vi } from "vitest";
import { withBrowserAuthLock } from "./browser-auth-lock";

describe("browser auth lock", () => {
  it("serializes cookie-rotating auth work through the browser lock manager", async () => {
    const original = Object.getOwnPropertyDescriptor(navigator, "locks");
    const request = vi.fn(
      async <T>(_name: string, operation: () => Promise<T>): Promise<T> =>
        operation(),
    );
    Object.defineProperty(navigator, "locks", {
      configurable: true,
      value: { request },
    });

    try {
      await expect(withBrowserAuthLock(async () => "completed")).resolves.toBe(
        "completed",
      );
      expect(request).toHaveBeenCalledWith(
        "quizopia-auth-refresh",
        expect.any(Function),
      );
    } finally {
      if (original === undefined) {
        delete (navigator as { locks?: LockManager }).locks;
      } else {
        Object.defineProperty(navigator, "locks", original);
      }
    }
  });
});
