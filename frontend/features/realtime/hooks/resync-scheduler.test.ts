import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createResyncScheduler } from "./resync-scheduler";

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

describe("createResyncScheduler", () => {
  it("coalesces a burst of signals into one refresh", async () => {
    const run = vi.fn();
    const scheduler = createResyncScheduler(run, {
      debounceMs: 500,
      minimumIntervalMs: 100,
    });
    for (let index = 0; index < 10; index++) scheduler.request();
    expect(run).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(600);
    expect(run).toHaveBeenCalledTimes(1);
    scheduler.dispose();
  });

  it("enforces a minimum interval between refreshes", async () => {
    const run = vi.fn();
    const scheduler = createResyncScheduler(run, {
      debounceMs: 50,
      minimumIntervalMs: 2_000,
    });
    scheduler.request();
    await vi.advanceTimersByTimeAsync(100);
    expect(run).toHaveBeenCalledTimes(1);
    scheduler.request();
    scheduler.request();
    await vi.advanceTimersByTimeAsync(100);
    expect(run).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(2_500);
    expect(run).toHaveBeenCalledTimes(2);
    scheduler.dispose();
  });

  it("collapses concurrent requests into one follow-up after the in-flight refresh", async () => {
    let release: (() => void) | null = null;
    const run = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    );
    const scheduler = createResyncScheduler(run, {
      debounceMs: 50,
      minimumIntervalMs: 0,
    });
    scheduler.request();
    await vi.advanceTimersByTimeAsync(60);
    expect(run).toHaveBeenCalledTimes(1);
    scheduler.request();
    scheduler.request();
    scheduler.request();
    release!();
    await vi.advanceTimersByTimeAsync(0);
    expect(run).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(60);
    expect(run).toHaveBeenCalledTimes(2);
    scheduler.dispose();
  });

  it("periodic ticks share the same bounds and reason routing", async () => {
    const run = vi.fn();
    const scheduler = createResyncScheduler(run, {
      debounceMs: 50,
      minimumIntervalMs: 0,
    });
    scheduler.tick();
    await vi.advanceTimersByTimeAsync(60);
    expect(run).toHaveBeenCalledTimes(1);
    expect(run.mock.calls[0][0]).toBe("periodic");
    scheduler.request();
    await vi.advanceTimersByTimeAsync(60);
    expect(run.mock.calls[1][0]).toBe("signal");
    scheduler.dispose();
  });

  it("dispose stops pending and future refreshes", async () => {
    const run = vi.fn();
    const scheduler = createResyncScheduler(run, {
      debounceMs: 50,
      minimumIntervalMs: 0,
    });
    scheduler.request();
    scheduler.dispose();
    await vi.advanceTimersByTimeAsync(200);
    expect(run).not.toHaveBeenCalled();
    scheduler.request();
    await vi.advanceTimersByTimeAsync(200);
    expect(run).not.toHaveBeenCalled();
  });
});
