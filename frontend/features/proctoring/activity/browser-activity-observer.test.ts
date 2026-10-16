import { describe, expect, it, vi } from "vitest";
import type { ActivityEvidenceStream } from "./activity-evidence-stream";
import type { BrowserEventInput } from "../model/monitoring";
import { attachBrowserActivityObserver } from "./browser-activity-observer";

describe("browser activity observer", () => {
  it("reports only supported page visibility, focus, fullscreen and network signals", () => {
    const observe = vi.fn<(input: BrowserEventInput) => boolean>(() => true);
    const stream = { observe } as unknown as ActivityEvidenceStream;
    const detach = attachBrowserActivityObserver({
      stream,
      attemptId: "e7b14962-3a2e-4d2d-926a-3b36ea90c199",
    });
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      value: "hidden",
    });
    document.dispatchEvent(new Event("visibilitychange"));
    window.dispatchEvent(new Event("blur"));
    window.dispatchEvent(new Event("focus"));
    Object.defineProperty(document, "fullscreenElement", {
      configurable: true,
      value: document.body,
    });
    document.dispatchEvent(new Event("fullscreenchange"));
    window.dispatchEvent(new Event("offline"));
    window.dispatchEvent(new Event("online"));
    expect(observe.mock.calls.map(([event]) => event.type)).toEqual([
      "VISIBILITY_HIDDEN",
      "WINDOW_BLUR",
      "WINDOW_FOCUS",
      "FULLSCREEN_ENTERED",
      "NETWORK_DISCONNECTED",
      "NETWORK_RECONNECTED",
    ]);
    expect(JSON.stringify(observe.mock.calls)).not.toContain("http");
    detach();
    window.dispatchEvent(new Event("blur"));
    expect(observe).toHaveBeenCalledTimes(6);
  });
});
