import type { ActivityEvidenceStream } from "./activity-evidence-stream";

/** Attach only observations a normal browser can genuinely expose for this page. */
export function attachBrowserActivityObserver(options: {
  readonly stream: ActivityEvidenceStream;
  readonly attemptId: string;
  readonly windowObject?: Window;
  readonly documentObject?: Document;
}): () => void {
  const windowObject = options.windowObject ?? window;
  const documentObject = options.documentObject ?? document;
  const cleanups: Array<() => void> = [];
  let duplicateReported = false;

  const listen = <K extends keyof WindowEventMap>(
    target: Window,
    type: K,
    listener: (event: WindowEventMap[K]) => void,
  ) => {
    target.addEventListener(type, listener);
    cleanups.push(() => target.removeEventListener(type, listener));
  };
  const listenDocument = <K extends keyof DocumentEventMap>(
    type: K,
    listener: (event: DocumentEventMap[K]) => void,
  ) => {
    documentObject.addEventListener(type, listener);
    cleanups.push(() => documentObject.removeEventListener(type, listener));
  };

  listenDocument("visibilitychange", () => {
    options.stream.observe({
      type:
        documentObject.visibilityState === "hidden"
          ? "VISIBILITY_HIDDEN"
          : "VISIBILITY_VISIBLE",
    });
  });
  listen(windowObject, "blur", () =>
    options.stream.observe({ type: "WINDOW_BLUR" }),
  );
  listen(windowObject, "focus", () =>
    options.stream.observe({ type: "WINDOW_FOCUS" }),
  );
  listenDocument("fullscreenchange", () =>
    options.stream.observe({
      type:
        documentObject.fullscreenElement === null
          ? "FULLSCREEN_EXITED"
          : "FULLSCREEN_ENTERED",
    }),
  );
  listen(windowObject, "offline", () =>
    options.stream.observe({ type: "NETWORK_DISCONNECTED" }),
  );
  listen(windowObject, "online", () =>
    options.stream.observe({ type: "NETWORK_RECONNECTED" }),
  );

  if (typeof BroadcastChannel !== "undefined") {
    const channel = new BroadcastChannel(
      `quizopia-monitoring-${options.attemptId}`,
    );
    const tabId = crypto.randomUUID();
    channel.onmessage = (event: MessageEvent<unknown>) => {
      if (event.data === tabId || duplicateReported) return;
      duplicateReported = true;
      options.stream.observe({ type: "DUPLICATE_TAB" });
    };
    channel.postMessage(tabId);
    cleanups.push(() => channel.close());
  }

  return () => cleanups.splice(0).forEach((cleanup) => cleanup());
}
