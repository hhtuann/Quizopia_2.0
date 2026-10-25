import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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

describe("WorkspaceTransitionProvider", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("shows an accessible Teaching overlay and settles when the destination home renders", () => {
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

    // A home rendered for a different workspace does not end the transition.
    fireEvent.click(screen.getByRole("button", { name: "notify learning" }));
    expect(screen.getByText("Changing your workspace…")).toBeInTheDocument();

    // The destination home ends it and moves focus to the main landmark.
    const main = document.getElementById("main-content");
    fireEvent.click(screen.getByRole("button", { name: "notify teaching" }));
    expect(screen.queryByText("Changing your workspace…")).toBeNull();
    expect(screen.getByText("idle")).toBeInTheDocument();
    act(() => {
      vi.runAllTimers();
    });
    expect(document.activeElement).toBe(main);
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
