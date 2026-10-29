import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { I18nProvider } from "../../lib/i18n/provider";
import { LanguageMenu } from "../ui/language-menu";
import {
  WorkspaceTransitionProvider,
  useWorkspaceTransition,
} from "./workspace-transition-overlay";

function Probe() {
  const { isTransitioning, startTransition, notifyWorkspaceHomeRendered } =
    useWorkspaceTransition();
  return (
    <div>
      <button
        onClick={() => {
          startTransition("TEACHING");
        }}
        type="button"
      >
        start
      </button>
      <button
        onClick={() => {
          startTransition("TEACHING");
        }}
        type="button"
      >
        start again
      </button>
      <button
        onClick={() => {
          notifyWorkspaceHomeRendered("LEARNING");
        }}
        type="button"
      >
        notify learning
      </button>
      <button
        onClick={() => {
          notifyWorkspaceHomeRendered("TEACHING");
        }}
        type="button"
      >
        notify teaching
      </button>
      <span>{isTransitioning ? "transitioning" : "idle"}</span>
      <main id="main-content" tabIndex={-1} />
    </div>
  );
}

function renderProbe() {
  return render(
    <WorkspaceTransitionProvider>
      <Probe />
    </WorkspaceTransitionProvider>,
  );
}

const MINIMUM_DISPLAY_MS = 2_500;

describe("WorkspaceTransitionProvider", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("localizes both transition lines and preserves the 2500 ms window across a language change", () => {
    render(
      <I18nProvider>
        <LanguageMenu />
        <WorkspaceTransitionProvider>
          <Probe />
        </WorkspaceTransitionProvider>
      </I18nProvider>,
    );
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    expect(screen.getByText("Đang chuyển không gian làm việc…")).toBeVisible();
    expect(
      screen.getByText("Đang chuyển sang không gian Giảng dạy"),
    ).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "notify teaching" }));
    act(() => {
      vi.advanceTimersByTime(1500);
    });
    fireEvent.click(
      screen.getByRole("button", { name: /Ngôn ngữ: |Language: / }),
    );
    fireEvent.click(screen.getByRole("menuitemradio", { name: "English" }));
    expect(screen.getByText("Switching to Teaching workspace")).toBeVisible();
    act(() => {
      vi.advanceTimersByTime(999);
    });
    expect(screen.getByText("Changing your workspace…")).toBeVisible();
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(
      screen.queryByText("Changing your workspace…"),
    ).not.toBeInTheDocument();
    expect(screen.getByText("idle")).toBeVisible();
  });

  it("shows an accessible Teaching overlay with branding", () => {
    renderProbe();
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    const status = screen
      .getByText("Switching to Teaching workspace")
      .closest('[role="status"]');
    expect(status).not.toBeNull();
    expect(status).toHaveAttribute("aria-live", "polite");
    expect(screen.getByText("Changing your workspace…")).toBeInTheDocument();
    expect(
      screen.getByText("Switching to Teaching workspace"),
    ).toBeInTheDocument();
    expect(screen.getByText("transitioning")).toBeInTheDocument();
    // Branding is part of the transition cover.
    expect(screen.getByTestId("quizopia-brand-mark")).toBeVisible();
  });

  it("keeps the overlay for the minimum display window when the destination is ready quickly", () => {
    renderProbe();
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    // Destination home renders after 100ms — far before the minimum window.
    act(() => {
      vi.advanceTimersByTime(100);
    });
    fireEvent.click(screen.getByRole("button", { name: "notify teaching" }));
    expect(screen.getByText("Changing your workspace…")).toBeInTheDocument();

    // Still visible right before the minimum window elapses…
    act(() => {
      vi.advanceTimersByTime(MINIMUM_DISPLAY_MS - 200);
    });
    expect(screen.getByText("Changing your workspace…")).toBeInTheDocument();
    // …and dismissed exactly when both conditions are satisfied.
    const main = document.getElementById("main-content");
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(screen.queryByText("Changing your workspace…")).toBeNull();
    expect(screen.getByText("idle")).toBeInTheDocument();
    act(() => {
      vi.runAllTimers();
    });
    expect(document.activeElement).toBe(main);
  });

  it("dismisses when a slow destination becomes ready after the minimum window", () => {
    renderProbe();
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    // Minimum window elapses without readiness: the overlay must NOT dismiss
    // merely because time passed.
    act(() => {
      vi.advanceTimersByTime(MINIMUM_DISPLAY_MS + 500);
    });
    expect(screen.getByText("Changing your workspace…")).toBeInTheDocument();

    // A different workspace's home never ends the transition.
    fireEvent.click(screen.getByRole("button", { name: "notify learning" }));
    expect(screen.getByText("Changing your workspace…")).toBeInTheDocument();

    // The destination home arriving at ~3000ms dismisses immediately.
    fireEvent.click(screen.getByRole("button", { name: "notify teaching" }));
    expect(screen.queryByText("Changing your workspace…")).toBeNull();
    expect(screen.getByText("idle")).toBeInTheDocument();
  });

  it("identifies the Learning destination", () => {
    render(
      <WorkspaceTransitionProvider>
        <LearningProbe />
      </WorkspaceTransitionProvider>,
    );
    fireEvent.click(screen.getByRole("button", { name: "start learning" }));
    expect(
      screen.getByText("Switching to Learning workspace"),
    ).toBeInTheDocument();
    expect(screen.queryByText("Switching to Teaching workspace")).toBeNull();
  });

  it("rejects duplicate transitions while one is in flight", () => {
    renderProbe();
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    fireEvent.click(screen.getByRole("button", { name: "start again" }));
    // One overlay only.
    expect(screen.getAllByText("Changing your workspace…")).toHaveLength(1);
    act(() => {
      vi.advanceTimersByTime(MINIMUM_DISPLAY_MS);
    });
    expect(screen.getAllByText("Changing your workspace…")).toHaveLength(1);
  });

  it("clears a stuck transition through the safety limit instead of blocking forever", () => {
    renderProbe();
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    expect(screen.queryByText("Changing your workspace…")).toBeNull();
    expect(screen.getByText("idle")).toBeInTheDocument();
  });

  it("keeps keyboard focus on the overlay and holds Tab during the transition", () => {
    renderProbe();
    fireEvent.click(screen.getByRole("button", { name: "start" }));
    const cover = document.activeElement as HTMLElement | null;
    expect(cover?.textContent).toContain("Changing your workspace…");
    const tab = new KeyboardEvent("keydown", {
      key: "Tab",
      bubbles: true,
      cancelable: true,
    });
    cover!.dispatchEvent(tab);
    expect(tab.defaultPrevented).toBe(true);
  });
});

function LearningProbe() {
  const { startTransition } = useWorkspaceTransition();
  return (
    <button
      onClick={() => {
        startTransition("LEARNING");
      }}
      type="button"
    >
      start learning
    </button>
  );
}
